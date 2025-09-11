package ru.miacomsoft.oracle_bd.rag.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class EmbeddingsText {
    private final String ollamaHost;
    private final int ollamaPort;
    private String embeddingModel;

    public EmbeddingsText(Properties properties) {
        this.ollamaHost = properties.getProperty("ollama.host", "localhost");
        this.ollamaPort = Integer.parseInt(properties.getProperty("ollama.port", "11434"));
        this.embeddingModel = properties.getProperty("ollama.embeddingModel", "all-minilm:22m");
    }

    public List<Double> getEmbeddings(String text) {
        try {
            URL url = new URL(String.format("http://%s:%d/api/embeddings", ollamaHost, ollamaPort));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            JSONObject payload = new JSONObject();
            payload.put("model", getEmbeddingModel());
            payload.put("prompt", text);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String response = br.lines().collect(Collectors.joining());
                    JSONObject jsonResponse = new JSONObject(response);
                    JSONArray embeddingArray = jsonResponse.getJSONArray("embedding");

                    List<Double> embeddings = new ArrayList<>();
                    for (int i = 0; i < embeddingArray.length(); i++) {
                        embeddings.add(embeddingArray.getDouble(i));
                    }
                    return embeddings;
                }
            } else {
                throw new RuntimeException("HTTP Error: " + conn.getResponseCode() + " - " + conn.getResponseMessage());
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения эмбеддингов: " + e.getMessage());
            throw new RuntimeException("Не удалось получить эмбеддинги для текста: " + text.substring(0, Math.min(50, text.length())), e);
        }
    }

    public static double calculateNorm(List<Double> embedding) {
        return Math.sqrt(embedding.stream().mapToDouble(d -> d * d).sum());
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }
}