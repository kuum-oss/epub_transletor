package com.translator.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TranslateService {

    private final OkHttpClient client;
    private final Gson gson;
    private static final String API_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=";

    public TranslateService() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public String translate(String text) {
        if (text == null || text.trim().isEmpty()) return text;

        try {
            Thread.sleep(300); // Пауза, чтобы Google не забанил
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            Request request = new Request.Builder()
                    .url(API_URL + encodedText)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return parseGoogleResponse(response.body().string());
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка перевода: " + e.getMessage());
        }
        return text; // Возвращаем оригинал при ошибке
    }

    private String parseGoogleResponse(String jsonResponse) {
        try {
            StringBuilder result = new StringBuilder();
            JsonArray rootArray = gson.fromJson(jsonResponse, JsonArray.class);
            JsonArray sentences = rootArray.get(0).getAsJsonArray();
            for (int i = 0; i < sentences.size(); i++) {
                result.append(sentences.get(i).getAsJsonArray().get(0).getAsString());
            }
            return result.toString();
        } catch (Exception e) {
            return jsonResponse;
        }
    }
}
