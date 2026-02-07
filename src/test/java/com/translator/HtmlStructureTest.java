package com.translator;

import com.translator.service.TranslateService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HtmlStructureTest {

    @Test
    public void testBoldTagsArePreserved() {
        // 1. Исходный HTML с вложенными тегами
        String html = "<p><b>Hello</b> world</p>";

        // 2. Создаем "фейковый" сервис БЕЗ MOCKITO
        // Мы просто переопределяем метод translate "на лету"
        TranslateService mockService = new TranslateService() {
            @Override
            public String translate(String text) {
                if (text.equals("Hello")) return "Привет";
                if (text.equals(" world")) return " мир";
                return text; // Возвращаем как есть, если не знаем перевода
            }
        };

        // 3. Запускаем ту же логику обработки, что и в процессоре
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);

        doc.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    TextNode textNode = (TextNode) node;
                    if (textNode.text().trim().length() > 0) {
                        String translated = mockService.translate(textNode.text());
                        textNode.text(translated);
                    }
                }
            }
            @Override
            public void tail(Node node, int depth) {}
        });

        // 4. Проверяем результат
        String result = doc.body().html();

        System.out.println("Было: " + html);
        System.out.println("Стало: " + result);

        // Ожидаем: <p><b>Привет</b> мир</p>
        Assertions.assertTrue(result.contains("<b>Привет</b>"), "Тег bold должен сохраниться!");
        Assertions.assertTrue(result.contains(" мир"), "Остальной текст должен быть переведен");
    }
}