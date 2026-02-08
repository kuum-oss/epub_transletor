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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EpubProcessor {

    private static final int BATCH_SIZE_LIMIT = 8000;
    private static final String SEP = " [[[...]]] ";

    public void process(String inputPath, String outputPath, TranslateService service) throws Exception {
        EpubReader reader = new EpubReader();
        Book book = reader.readEpub(new FileInputStream(inputPath));

        ExecutorService executor = Executors.newFixedThreadPool(1);

        List<Resource> contents = book.getContents();
        System.out.println(">>> ПОЧАТОК ОБРОБКИ КНИГИ: " + book.getTitle());

        for (int i = 0; i < contents.size(); i++) {
            Resource resource = contents.get(i);
            final int index = i + 1;

            if (resource.getMediaType().getName().contains("html")) {
                executor.submit(() -> {
                    try {
                        String pageName = (resource.getTitle() != null) ? resource.getTitle() : resource.getHref();
                        System.out.println("\n--- ОБРОБКА СТОРІНКИ [" + index + "/" + contents.size() + "]: " + pageName + " ---");

                        Document doc = Jsoup.parse(new String(resource.getData(), "UTF-8"));
                        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false);

                        if (doc.body() != null) {
                            translateChapter(doc.body(), service, pageName);
                        }

                        resource.setData(doc.outerHtml().getBytes("UTF-8"));
                    } catch (Exception e) {
                        System.err.println("Помилка на сторінці " + index + ": " + e.getMessage());
                    }
                });
            }
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.DAYS);

        new EpubWriter().write(book, new FileOutputStream(outputPath));
        System.out.println("\nГотово! Книгу збережено: " + outputPath);
    }

    private void translateChapter(Node root, TranslateService service, String pageName) {
        List<TextNode> allNodes = new ArrayList<>();
        collectNodes(root, allNodes);

        List<TextNode> batchNodes = new ArrayList<>();
        StringBuilder batchText = new StringBuilder();
        int batchCounter = 1;

        for (TextNode node : allNodes) {
            String text = node.getWholeText();
            if (text.strip().length() < 2) continue;

            batchNodes.add(node);
            batchText.append(text).append(SEP);

            if (batchText.length() > BATCH_SIZE_LIMIT) {
                System.out.println("[" + pageName + "] Надсилаю пакет #" + batchCounter + " (" + batchText.length() + " симв.)");
                processBatch(batchText.toString(), batchNodes, service);
                batchCounter++;
                batchText.setLength(0);
                batchNodes.clear();
            }
        }
        if (!batchNodes.isEmpty()) {
            System.out.println("[" + pageName + "] Надсилаю останній пакет #" + batchCounter);
            processBatch(batchText.toString(), batchNodes, service);
        }
    }

    private void processBatch(String textToSend, List<TextNode> nodes, TranslateService service) {
        // Короткое превью оригинала (на всякий случай)
        String previewOrig = textToSend.substring(0, Math.min(textToSend.length(), 100)).replace("\n", " ");
        System.out.println("  > Оригинал: " + previewOrig + "...");

        // Отправка на перевод
        String translated = service.translateBatch(textToSend);

        if (translated != null && !translated.isEmpty()) {
            // Вывод до 1000 символов ПЕРЕВОДА в консоль
            int logLength = Math.min(translated.length(), 1000);
            String transPreview = translated.substring(0, logLength);

            System.out.println("\n--- ПОЛУЧЕН ПЕРЕВОД (кусок " + logLength + " симв.) ---");
            System.out.println(transPreview);
            if (translated.length() > 1000) System.out.println("... [далее текст скрыт в логах]");
            System.out.println("---------------------------------------------------\n");

            // Разбивка по разделителю и вставка в книгу
            String[] parts = translated.split("\\[\\[\\[\\.\\.\\.\\]\\]\\]");
            for (int i = 0; i < nodes.size(); i++) {
                if (i < parts.length) {
                    nodes.get(i).text(parts[i].trim());
                }
            }
        } else {
            System.err.println("  ! Ошибка: Нейросеть вернула пустой ответ или произошел таймаут.");
        }
    }

    private void collectNodes(Node node, List<TextNode> list) {
        if (node instanceof TextNode) {
            list.add((TextNode) node);
        } else {
            for (Node child : node.childNodes()) collectNodes(child, list);
        }
    }
}