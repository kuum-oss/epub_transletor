package com.translator;

import com.translator.service.TranslateService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServiceTest {

    @Test
    public void testEmptyStringReturnsEmpty() {
        TranslateService service = new TranslateService();
        String result = service.translate("");
        Assertions.assertEquals("", result);
    }

    @Test
    public void testNullReturnsNull() {
        TranslateService service = new TranslateService();
        String result = service.translate(null);
        Assertions.assertNull(result);
    }

    // ВНИМАНИЕ: Этот тест делает реальный запрос в Google.
    // Если интернета нет, он упадет.
    @Test
    public void testRealGoogleCall() {
        TranslateService service = new TranslateService();
        String original = "Hello";
        String result = service.translate(original);

        System.out.println("Google перевел 'Hello' как: " + result);

        // Проверяем, что в ответе есть кириллица или слово "Привет"
        // (Google может вернуть "Здравствуйте", так что проверяем просто на смену языка)
        Assertions.assertNotEquals(original, result);
    }
}