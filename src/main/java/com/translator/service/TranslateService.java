package com.translator.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class TranslateService {
    private final OkHttpClient client;
    private final Gson gson;
    private static final String OLLAMA_URL = "http://127.0.0.1:11434/api/chat";

    public TranslateService() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(900, TimeUnit.SECONDS) // 15 минут для 8к символов
                .build();
        this.gson = new Gson();
    }

    public String translateBatch(String text) {
        if (text == null || text.trim().isEmpty()) return text;

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", "llama3");
        requestJson.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content",
                "You are an expert literary translator. Translate the provided book text into Russian.\n" +
                        "CRITICAL RULES:\n" +
                        "1. Preserve the separator '[[[...]]]' exactly. It MUST be in the output.\n" +
                        "2. NO explanations, NO introductory text like 'Here is the translation'.\n" +
                        "3. Use professional, novel-style Russian.\n" +
                        "4. Maintain paragraph breaks.");
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", text);
        messages.add(userMessage);

        requestJson.add("messages", messages);

        RequestBody body = RequestBody.create(
                requestJson.toString(), MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder().url(OLLAMA_URL).post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String respBody = response.body().string();
                JsonObject jsonResponse = gson.fromJson(respBody, JsonObject.class);
                String content = jsonResponse.getAsJsonObject("message").get("content").getAsString().trim();

                // Очистка от возможного мусора ИИ в начале
                if (content.toLowerCase().contains("here is") && content.contains("[[[...]]]")) {
                    content = content.substring(content.indexOf("[[[...]]]"));
                }
                return content;
            }
        } catch (IOException e) {
            System.err.println("Ошибка Ollama: " + e.getMessage());
        }
        return null;
    }
}