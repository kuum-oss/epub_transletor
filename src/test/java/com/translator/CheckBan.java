package com.translator;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class CheckBan {

    public static void main(String[] args) {
        System.out.println("🔍 Проверяем статус IP...");

        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=Hello%20World";

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0") // Притворяемся браузером
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();

            System.out.println("------------------------------------------------");
            if (code == 200) {
                System.out.println("✅ ВСЁ ОТЛИЧНО! (Код 200)");
                System.out.println("Google отвечает. Твой IP чист.");
                System.out.println("Если программа не работает — проблема в коде, а не в бане.");
            } else if (code == 429) {
                System.out.println("⛔ БАН ПО IP! (Код 429)");
                System.out.println("Google заблокировал запросы с твоего адреса.");
                System.out.println("Решение: Включи VPN или подожди 1-2 часа.");
            } else if (code == 403) {
                System.out.println("🔒 ДОСТУП ЗАПРЕЩЕН (Код 403)");
                System.out.println("Возможно, проблема с User-Agent или капчей.");
            } else {
                System.out.println("⚠️ СТРАННЫЙ ОТВЕТ: " + code);
                System.out.println("Сообщение: " + response.message());
            }
            System.out.println("------------------------------------------------");

        } catch (IOException e) {
            System.err.println("❌ ОШИБКА СЕТИ: Не удалось соединиться с Google.");
            System.out.println("Проверь интернет-соединение.");
            e.printStackTrace();
        }
    }
}