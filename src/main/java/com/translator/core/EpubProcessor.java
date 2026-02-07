package com.translator.core;

import com.translator.service.TranslateService;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.epub.EpubWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.jsoup.nodes.Entities;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class EpubProcessor {

    private int totalElementsInBook = 0;
    private int currentElementProgress = 0;

    // Разделитель, который Google скорее всего не переведет и не удалит
    private static final String DELIMITER = " ||| ";
    private static final int BATCH_SIZE_LIMIT = 1800; // Символов в одном запросе (безопасный лимит)

    public void process(String inputPath, String outputPath, TranslateService service) {
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            System.out.println("⏳ Читаем книгу и считаем объем работы...");
            Book book = new EpubReader().readEpub(fis);
            List<Resource> contents = book.getContents();

            totalElementsInBook = countTotalElements(contents);
            System.out.println("Найдено фрагментов: " + totalElementsInBook);
            System.out.println("--- Старт быстрого перевода ---");

            for (Resource resource : contents) {
                if (isHtml(resource)) {
                    translateResource(resource, service);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                new EpubWriter().write(book, fos);
            }
            drawProgressBar(totalElementsInBook, totalElementsInBook);
            System.out.println("\n✅ Готово! Книга сохранена.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isHtml(Resource resource) {
        return resource.getMediaType().getName().contains("html") ||
                resource.getMediaType().getName().contains("xml");
    }

    private int countTotalElements(List<Resource> contents) {
        int count = 0;
        try {
            for (Resource resource : contents) {
                if (isHtml(resource)) {
                    String html = new String(resource.getData(), resource.getInputEncoding());
                    Document doc = Jsoup.parse(html);
                    // Считаем текстовые узлы
                    final int[] localCount = {0};
                    doc.traverse(new NodeVisitor() {
                        public void head(Node node, int depth) {
                            if (node instanceof TextNode && ((TextNode) node).text().trim().length() > 0) {
                                localCount[0]++;
                            }
                        }
                        public void tail(Node node, int depth) {}
                    });
                    count += localCount[0];
                }
            }
        } catch (Exception e) { }
        return count;
    }

    private void translateResource(Resource resource, TranslateService service) {
        try {
            String encoding = resource.getInputEncoding();
            if (encoding == null) encoding = "UTF-8";

            String html = new String(resource.getData(), encoding);
            Document doc = Jsoup.parse(html);

            doc.outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(true);

            // 1. СОБИРАЕМ ВСЕ ТЕКСТОВЫЕ УЗЛЫ В СПИСОК
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

            // 2. ОБРАБАТЫВАЕМ ИХ ПАЧКАМИ (BATCHING)
            StringBuilder batchText = new StringBuilder();
            List<TextNode> currentBatchNodes = new ArrayList<>();

            for (TextNode node : nodesToTranslate) {
                String text = node.text();

                // Если добавление этого текста превысит лимит - отправляем текущую пачку
                if (batchText.length() + text.length() + DELIMITER.length() > BATCH_SIZE_LIMIT) {
                    processBatch(batchText, currentBatchNodes, service);

                    // Очищаем для новой пачки
                    batchText.setLength(0);
                    currentBatchNodes.clear();
                }

                if (batchText.length() > 0) {
                    batchText.append(DELIMITER);
                }
                batchText.append(text);
                currentBatchNodes.add(node);
            }

            // Обрабатываем остатки
            if (!currentBatchNodes.isEmpty()) {
                processBatch(batchText, currentBatchNodes, service);
            }

            resource.setData(doc.outerHtml().getBytes(encoding));

        } catch (Exception e) {
            System.err.println("Сбой в главе: " + e.getMessage());
        }
    }

    private void processBatch(StringBuilder batchText, List<TextNode> nodes, TranslateService service) {
        if (nodes.isEmpty()) return;

        String originalBigString = batchText.toString();
        String translatedBigString = service.translate(originalBigString);

        // Разбиваем полученный перевод обратно по разделителю
        // Используем Pattern.quote, чтобы спецсимволы в разделителе не ломали regex
        String[] parts = translatedBigString.split(Pattern.quote(DELIMITER.trim()));

        // ПРОВЕРКА БЕЗОПАСНОСТИ:
        // Если количество кусков совпадает, всё супер.
        // Если нет (Google съел разделитель), мы переводим эти узлы по отдельности (медленно, но надежно)
        if (parts.length == nodes.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                nodes.get(i).text(parts[i].trim());
                updateProgress();
            }
        } else {
            // FALLBACK: Если пачка сломалась, переводим по одному
            // System.out.println("⚠ Пачка рассыпалась, переводим поштучно...");
            for (TextNode node : nodes) {
                String singleTrans = service.translate(node.text());
                node.text(singleTrans);
                updateProgress();
            }
        }
    }

    private void updateProgress() {
        currentElementProgress++;
        // Обновляем полоску реже, чтобы не тормозить консоль
        if (currentElementProgress % 10 == 0 || currentElementProgress == totalElementsInBook) {
            drawProgressBar(currentElementProgress, totalElementsInBook);
        }
    }

    private void drawProgressBar(int current, int total) {
        int width = 40;
        double percent = (double) current / total;
        if (percent > 1.0) percent = 1.0;
        int filled = (int) (percent * width);

        StringBuilder bar = new StringBuilder("\r[");
        for (int i = 0; i < width; i++) {
            if (i < filled) bar.append("=");
            else bar.append(" ");
        }
        int percentInt = (int) (percent * 100);
        bar.append("] ").append(percentInt).append("%");
        System.out.print(bar.toString());
    }
}