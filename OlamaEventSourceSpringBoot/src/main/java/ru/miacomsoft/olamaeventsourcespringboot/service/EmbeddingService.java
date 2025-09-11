package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final String OLLAMA_HOST = "http://192.168.15.6:11434";
    private static String EMBEDDING_NAME = "all-minilm:22m";
    private final HttpClient client;

    public EmbeddingService() {
        this.client = HttpClient.newHttpClient();
    }

    public List<Double> getEmbeddings(String text) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_HOST + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            new JSONObject()
                                    .put("model", EMBEDDING_NAME)
                                    .put("prompt", text)
                                    .toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONArray embeddingArray = jsonResponse.getJSONArray("embedding");
                List<Double> embeddings = new ArrayList<>();
                for (int i = 0; i < embeddingArray.length(); i++) {
                    embeddings.add(embeddingArray.getDouble(i));
                }
                return embeddings;
            } else {
                throw new RuntimeException("HTTP Error: " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения эмбеддингов: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void setEmbeddingName(String modelName) {
        EMBEDDING_NAME = modelName;
    }

    public String getEmbeddingName() {
        return EMBEDDING_NAME;
    }
}