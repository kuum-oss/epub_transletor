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
import org.jsoup.nodes.Entities;
import org.jsoup.select.NodeVisitor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class EpubProcessor {

    private int totalElementsInBook = 0;
    private int currentElementProgress = 0;
    private int lastPrintedPercent = -1;

    private static final String DELIMITER = " ||| ";
    private static final int BATCH_SIZE_LIMIT = 1800;

    // СЛОВАРЬ ИСПРАВЛЕНИЙ (Только универсальные и грубые ошибки)
    private static final Map<String, String> CORRECTIONS = new LinkedHashMap<>();
    static {
        // --- 1. ИМЕНА СОБСТВЕННЫЕ ---
        CORRECTIONS.put("австралиец", "Осси");
        CORRECTIONS.put("Австралиец", "Осси");
        CORRECTIONS.put("VIX", "Викс");
        CORRECTIONS.put("Vix", "Викс");

        // --- 2. ЖАНРОВЫЕ ТЕРМИНЫ ---
        // Исправляем "subwoofer" -> "sub"
        CORRECTIONS.put("сабвуфер", "саб");
        CORRECTIONS.put("Сабвуфер", "Саб");
        CORRECTIONS.put("сабвуфера", "саба");
        CORRECTIONS.put("сабвуферу", "сабу");
        CORRECTIONS.put("сабвуфером", "сабом");
        CORRECTIONS.put("сабвуфере", "сабе");

        // --- ГЕНДЕРНЫЕ ИСПРАВЛЕНИЯ УБРАНЫ (Чтобы книга была универсальной) ---
    }

    public void process(String inputPath, String outputPath, TranslateService service) {
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            System.out.println("⏳ Читаем книгу и считаем объем работы...");
            Book book = new EpubReader().readEpub(fis);
            List<Resource> contents = book.getContents();

            totalElementsInBook = countTotalElements(contents);
            System.out.println("Найдено фрагментов текста: " + totalElementsInBook);
            System.out.println("--- Старт перевода (Универсальный режим) ---");

            drawProgressBar(0, totalElementsInBook);

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

            // ВАЖНО: prettyPrint(false) сохраняет оригинальные шрифты и верстку
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

            resource.setData(doc.outerHtml().getBytes(encoding));

        } catch (Exception e) {
            System.err.println("Сбой в главе: " + e.getMessage());
        }
    }

    private void processBatch(StringBuilder batchText, List<TextNode> nodes, TranslateService service) {
        if (nodes.isEmpty()) return;

        String originalBigString = batchText.toString();
        String translatedBigString = service.translate(originalBigString);

        if (translatedBigString == null) translatedBigString = originalBigString;

        // Применяем словарь (только имена и термины)
        translatedBigString = applyCorrections(translatedBigString);

        String[] parts = translatedBigString.split(Pattern.quote(DELIMITER.trim()));

        if (parts.length == nodes.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                String translatedPart = parts[i];
                // Сохраняем пробелы по краям
                if (nodes.get(i).text().startsWith(" ") && !translatedPart.startsWith(" ")) {
                    translatedPart = " " + translatedPart;
                }
                nodes.get(i).text(translatedPart);
                updateProgress();
            }
        } else {
            // Фолбэк по одному (на случай сбоя разделителя)
            for (TextNode node : nodes) {
                String singleTrans = service.translate(node.text());
                singleTrans = applyCorrections(singleTrans);
                if (node.text().startsWith(" ") && !singleTrans.startsWith(" ")) {
                    singleTrans = " " + singleTrans;
                }
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
        currentElementProgress++;
        int percent = (int) ((double) currentElementProgress / totalElementsInBook * 100);
        if (percent > lastPrintedPercent) {
            drawProgressBar(currentElementProgress, totalElementsInBook);
            lastPrintedPercent = percent;
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