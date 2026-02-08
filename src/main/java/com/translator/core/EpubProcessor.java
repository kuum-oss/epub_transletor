package com.translator.core;

import com.translator.service.TranslateService;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.epub.EpubWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class EpubProcessor {

    private final AtomicInteger totalElementsInBook = new AtomicInteger(0);
    private final AtomicInteger currentElementProgress = new AtomicInteger(0);
    private int lastPrintedPercent = -1;

    private static final String DELIMITER = " ||| ";
    private static final int BATCH_SIZE_LIMIT = 1800; // Оптимальный размер пакета
    private static final int THREAD_COUNT = 3; // 3 потока — безопасно для VPN

    // КЕШ ДЛЯ ПЕРЕВОДА (Чтобы не бить файл при ошибках)
    private final Map<Resource, byte[]> translatedResourcesCache = new ConcurrentHashMap<>();

    private static final Map<String, String> CORRECTIONS = new LinkedHashMap<>();

    static {
        // =====================
        // ПЕРСОНАЖИ
        // =====================

        // Vane (GT: флюгер / лопасть)
        CORRECTIONS.put("Флюгер", "Вейн");
        CORRECTIONS.put("Флюгера", "Вейна");
        CORRECTIONS.put("Флюгеру", "Вейну");
        CORRECTIONS.put("Флюгером", "Вейном");
        CORRECTIONS.put("Флюгере", "Вейне");
        CORRECTIONS.put("лопасть", "Вейн"); // редко, но встречается

        // Bash (GT: удар, вечеринка)
        CORRECTIONS.put("Удар", "Баш");
        CORRECTIONS.put("Удара", "Баша");
        CORRECTIONS.put("Удару", "Башу");
        CORRECTIONS.put("Ударом", "Башем");
        CORRECTIONS.put("Ударе", "Баше");
        CORRECTIONS.put("Баши", "Баш"); // множественное искажение

        // Kas (GT: касса)
        CORRECTIONS.put("Касса", "Кас");
        CORRECTIONS.put("Кассы", "Каса");
        CORRECTIONS.put("Кассе", "Касе");

        // Darling (фамилия)
        CORRECTIONS.put("Дорогая", "Дарлинг");
        CORRECTIONS.put("Милая", "Дарлинг");
        CORRECTIONS.put("Любимая", "Дарлинг");

        // Pan
        CORRECTIONS.put("Кастрюля", "Пэн");
        CORRECTIONS.put("Сковорода", "Пэн");
        CORRECTIONS.put("Пан", "Пэн");
        CORRECTIONS.put("Пана", "Пэна");

        // =====================
        // ТЕРМИНЫ МИРА
        // =====================

        // Fae → фейри (НЕ склоняется)
        CORRECTIONS.put("Фея", "Фейри");
        CORRECTIONS.put("Феи", "Фейри");
        CORRECTIONS.put("Феям", "Фейри");
        CORRECTIONS.put("Феями", "Фейри");

        // Neverland
        CORRECTIONS.put("Нетландия", "Неверленд");
        CORRECTIONS.put("Нетландии", "Неверленда");
        CORRECTIONS.put("Нетландию", "Неверленд");
        CORRECTIONS.put("Нетландией", "Неверлендом");

        // Shadow (персонаж/сущность, только с заглавной)
        CORRECTIONS.put("Тень", "Тень");
        CORRECTIONS.put("Тени", "Тени");
    }

    public void process(String inputPath, String outputPath, TranslateService service) {
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            System.out.println("⏳ Читаем структуру книги...");
            Book book = new EpubReader().readEpub(fis);
            List<Resource> contents = book.getContents();

            int total = countTotalElements(contents);
            totalElementsInBook.set(total);

            System.out.println("Найдено фрагментов: " + total);
            System.out.println("🚀 Старт перевода в " + THREAD_COUNT + " потока...");
            drawProgressBar(0, total);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

            for (Resource resource : contents) {
                if (isHtml(resource)) {
                    // Запускаем задачу, которая положит результат в Cache
                    executor.submit(() -> translateResourceAndCache(resource, service));
                }
            }

            executor.shutdown();
            // Ждем завершения ВСЕХ потоков
            boolean finished = executor.awaitTermination(2, TimeUnit.HOURS);

            if (finished) {
                System.out.println("\n💾 Сборка финального файла...");
                // Применяем переводы из кеша к книге (безопасно, в одном потоке)
                for (Map.Entry<Resource, byte[]> entry : translatedResourcesCache.entrySet()) {
                    entry.getKey().setData(entry.getValue());
                }

                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    new EpubWriter().write(book, fos);
                }
                drawProgressBar(total, total);
                System.out.println("\n✅ УСПЕХ! Книга сохранена: " + outputPath);
            } else {
                System.err.println("\n❌ Ошибка: Время ожидания истекло.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("\n❌ Критическая ошибка: " + e.getMessage());
        }
    }

    private boolean isHtml(Resource resource) {
        String name = resource.getMediaType().getName().toLowerCase();
        return name.contains("html") || name.contains("xml");
    }

    private int countTotalElements(List<Resource> contents) {
        int count = 0;
        try {
            for (Resource resource : contents) {
                if (isHtml(resource)) {
                    String html = new String(resource.getData(), resource.getInputEncoding());
                    Document doc = Jsoup.parse(html);
                    count += doc.select("*:not(:has(*))").size();
                }
            }
        } catch (Exception e) { }
        return count;
    }

    private void translateResourceAndCache(Resource resource, TranslateService service) {
        try {
            String encoding = resource.getInputEncoding();
            if (encoding == null) encoding = "UTF-8";

            String html = new String(resource.getData(), encoding);
            Document doc = Jsoup.parse(html);

            // Настройки для сохранения валидного XHTML
            doc.outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(false);

            List<TextNode> nodesToTranslate = new ArrayList<>();
            doc.traverse(new NodeVisitor() {
                @Override
                public void head(Node node, int depth) {
                    if (node instanceof TextNode) {
                        TextNode textNode = (TextNode) node;
                        if (textNode.text().trim().length() > 0) {
                            nodesToTranslate.add(textNode);
                        }
                    }
                }
                @Override
                public void tail(Node node, int depth) {}
            });

            StringBuilder batchText = new StringBuilder();
            List<TextNode> currentBatchNodes = new ArrayList<>();

            for (TextNode node : nodesToTranslate) {
                String text = node.text();

                if (batchText.length() + text.length() + DELIMITER.length() > BATCH_SIZE_LIMIT) {
                    processBatch(batchText, currentBatchNodes, service);
                    batchText.setLength(0);
                    currentBatchNodes.clear();
                }

                if (batchText.length() > 0) {
                    batchText.append(DELIMITER);
                }
                batchText.append(text);
                currentBatchNodes.add(node);
            }

            if (!currentBatchNodes.isEmpty()) {
                processBatch(batchText, currentBatchNodes, service);
            }

            // ВАЖНО: Кладем результат в кеш, не трогая саму книгу пока что
            translatedResourcesCache.put(resource, doc.outerHtml().getBytes(encoding));

        } catch (Exception e) {
            System.err.println("Сбой в потоке: " + e.getMessage());
        }
    }

    private void processBatch(StringBuilder batchText, List<TextNode> nodes, TranslateService service) {
        if (nodes.isEmpty()) return;

        String originalBigString = batchText.toString();
        // Используем метод с повторами
        String translatedBigString = service.translateWithRetry(originalBigString);

        if (translatedBigString == null) translatedBigString = originalBigString;

        translatedBigString = applyCorrections(translatedBigString);

        String[] parts = translatedBigString.split(Pattern.quote(DELIMITER.trim()));

        if (parts.length == nodes.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                String translatedPart = parts[i];
                // Сохраняем пробелы в начале (частая проблема при склейке)
                if (nodes.get(i).text().startsWith(" ") && !translatedPart.startsWith(" ")) {
                    translatedPart = " " + translatedPart;
                }
                nodes.get(i).text(translatedPart);
                updateProgress();
            }
        } else {
            // Если склейка сломалась, переводим по одному (медленно, но точно)
            for (TextNode node : nodes) {
                String singleTrans = service.translateWithRetry(node.text());
                singleTrans = applyCorrections(singleTrans);
                node.text(singleTrans);
                updateProgress();
            }
        }
    }

    private String applyCorrections(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : CORRECTIONS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void updateProgress() {
        int current = currentElementProgress.incrementAndGet();
        int total = totalElementsInBook.get();

        if (total == 0) return;

        int percent = (int) ((double) current / total * 100);
        // Синхронизация вывода, чтобы консоль не моргала
        synchronized (this) {
            if (percent > lastPrintedPercent) {
                drawProgressBar(current, total);
                lastPrintedPercent = percent;
            }
        }
    }

    private void drawProgressBar(int current, int total) {
        int width = 30;
        double percent = (double) current / total;
        if (percent > 1.0) percent = 1.0;
        int filled = (int) (percent * width);

        StringBuilder bar = new StringBuilder();
        bar.append("\r[");
        for (int i = 0; i < width; i++) {
            if (i < filled) bar.append("=");
            else bar.append(" ");
        }
        int percentInt = (int) (percent * 100);
        bar.append("] ").append(percentInt).append("%");
        System.out.print(bar.toString());
    }
}