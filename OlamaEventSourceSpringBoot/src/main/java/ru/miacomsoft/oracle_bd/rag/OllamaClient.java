package ru.miacomsoft.oracle_bd.rag;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.oracle_bd.rag.utils.EmbeddingsText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class OllamaClient {
    private final String ollamaUrl;
    private final EmbeddingsText embeddingsText;
    private final Properties properties;
    private int connectTimeout = 30000;
    private int readTimeout = 30000;

    // Добавляем поле для отслеживания отмены
    private AtomicBoolean cancellationFlag = null;

    public OllamaClient(Properties properties) {
        this.properties = properties;
        this.ollamaUrl = String.format("http://%s:%s",
                properties.getProperty("ollama.host", "localhost"),
                properties.getProperty("ollama.port", "11434"));
        this.embeddingsText = new EmbeddingsText(properties);
    }

    // Методы для управления отменой
    public void setCancellationFlag(AtomicBoolean flag) {
        this.cancellationFlag = flag;
    }

    public void clearCancellationFlag() {
        this.cancellationFlag = null;
    }

    private boolean isCancelled() {
        return cancellationFlag != null && cancellationFlag.get();
    }

    public List<Double> getEmbedding(String text) {
        return embeddingsText.getEmbeddings(text);
    }

    public String generateResponse(String model, List<ChatMessage> messages,
                                   boolean stream, Consumer<String> streamConsumer) throws IOException {
        return generateResponse(model, messages, 0.7, stream, streamConsumer);
    }

    public String generateResponse(String model, List<ChatMessage> messages, double temperature,
                                   boolean stream, Consumer<String> streamConsumer) throws IOException {
        String apiUrl = ollamaUrl + "/api/chat";

        // Проверяем доступность Ollama
        if (!isOllamaAvailable()) {
            throw new IOException("Ollama сервер недоступен по адресу: " + ollamaUrl);
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        requestBody.put("stream", stream || streamConsumer != null);

        JSONArray messagesArray = new JSONArray();
        for (ChatMessage message : messages) {
            messagesArray.put(new JSONObject()
                    .put("role", message.role)
                    .put("content", message.content));
        }

        requestBody.put("messages", messagesArray);

        HttpURLConnection connection = createConnection(apiUrl);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        if (stream || streamConsumer != null) {
            return handleStreamResponse(connection, streamConsumer);
        } else {
            return handleJsonResponse(connection);
        }
    }

    private boolean isOllamaAvailable() {
        try {
            URL url = new URL(ollamaUrl + "/api/tags");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int responseCode = connection.getResponseCode();
            return responseCode == 200;
        } catch (IOException e) {
            System.err.println("Ollama сервер недоступен: " + e.getMessage());
            return false;
        }
    }

    private String handleStreamResponse(HttpURLConnection connection, Consumer<String> streamConsumer) throws IOException {
        StringBuilder fullResponse = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "utf-8"))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                // Проверяем отмену
                if (isCancelled()) {
                    connection.disconnect();
                    return "[CANCELLED]";
                }

                if (!responseLine.trim().isEmpty()) {
                    JSONObject chunk = new JSONObject(responseLine);
                    if (chunk.has("message") && chunk.getJSONObject("message").has("content")) {
                        String content = chunk.getJSONObject("message").getString("content");
                        fullResponse.append(content);
                        if (streamConsumer != null) {
                            streamConsumer.accept(content);
                        }
                    } else if (chunk.has("done") && chunk.getBoolean("done")) {
                        if (streamConsumer != null) {
                            streamConsumer.accept("[DONE]");
                        }
                    }
                }
            }
        }
        return fullResponse.toString();
    }

    private String handleJsonResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                // Проверяем отмену
                if (isCancelled()) {
                    connection.disconnect();
                    return "[CANCELLED]";
                }

                response.append(responseLine.trim());
            }

            JSONObject responseBody = new JSONObject(response.toString());
            return responseBody.getJSONObject("message").getString("content");
        }
    }

    private HttpURLConnection createConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        return connection;
    }


    public void pullModel(String modelName, Consumer<String> progressConsumer) throws IOException {
        String apiUrl = ollamaUrl + "/api/pull";

        JSONObject requestBody = new JSONObject();
        requestBody.put("name", modelName);
        requestBody.put("stream", true); // Включаем streaming для получения прогресса

        HttpURLConnection connection = createConnection(apiUrl);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "utf-8"))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                // Проверяем отмену
                if (isCancelled()) {
                    connection.disconnect();
                    return;
                }

                if (!responseLine.trim().isEmpty()) {
                    JSONObject chunk = new JSONObject(responseLine);

                    if (chunk.has("status")) {
                        String status = chunk.getString("status");
                        if (progressConsumer != null) {
                            progressConsumer.accept(status);
                        }
                    } else if (chunk.has("completed") && chunk.getBoolean("completed")) {
                        if (progressConsumer != null) {
                            progressConsumer.accept("Загрузка завершена");
                        }
                        break;
                    }
                }
            }
        }
    }

    public void deleteModel(String modelName) throws IOException {
        String apiUrl = ollamaUrl + "/api/delete";

        JSONObject requestBody = new JSONObject();
        requestBody.put("name", modelName);

        HttpURLConnection connection = createConnection(apiUrl);
        connection.setRequestMethod("DELETE");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Ошибка удаления модели: HTTP " + responseCode);
        }

        // Читаем ответ для проверки
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "utf-8"))) {
            String response = br.lines().collect(Collectors.joining());
            JSONObject responseJson = new JSONObject(response);
            if (!responseJson.optBoolean("success", false)) {
                throw new IOException("Не удалось удалить модель: " + response);
            }
        }
    }

    public void setConnectTimeout(int timeout, TimeUnit unit) {
        this.connectTimeout = (int) unit.toMillis(timeout);
    }

    public void setReadTimeout(int timeout, TimeUnit unit) {
        this.readTimeout = (int) unit.toMillis(timeout);
    }

    public String getDefaultModel() {
        return properties.getProperty("ollama.model", "llama2");
    }
    public String getEmbeddingModel() {
        return embeddingsText.getEmbeddingModel();
    }
    public void setEmbeddingModel(String  model) {
        embeddingsText.setEmbeddingModel(model);
    }



    public void setDefaultModel(String model) {
        if (model != null && !model.trim().isEmpty()) {
            properties.setProperty("ollama.model", model.trim());
        }
    }

    public List<Map<String, Object>> getAvailableModelsWithDetails() {
        List<Map<String, Object>> models = new ArrayList<>();
        try {
            URL url = new URL(String.format("%s/api/tags", ollamaUrl));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String response = br.lines().collect(Collectors.joining());
                    JSONObject jsonResponse = new JSONObject(response);
                    JSONArray modelsArray = jsonResponse.getJSONArray("models");

                    for (int i = 0; i < modelsArray.length(); i++) {
                        JSONObject model = modelsArray.getJSONObject(i);
                        Map<String, Object> modelInfo = new HashMap<>();
                        modelInfo.put("name", model.getString("name"));
                        modelInfo.put("size", model.optLong("size", 0));
                        modelInfo.put("modified", model.optString("modified_at", ""));
                        models.add(modelInfo);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения списка моделей: " + e.getMessage());
        }
        return models;
    }
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "i";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public Iterator<String> generateResponseStream(String query, String context, String prompt,boolean isStream) {
        if (prompt.contains("{context}")) {
            prompt = prompt.replace("{context}", context);
        }
        if (prompt.contains("{query}")) {
            prompt = prompt.replace("{query}", query);
        }

        String finalPrompt = prompt;
        return new Iterator<String>() {
            private HttpURLConnection conn;
            private BufferedReader reader;
            private String nextToken;

            {
                try {
                    URL url = new URL(String.format("%s/api/generate", ollamaUrl));
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    JSONObject payload = new JSONObject();
                    payload.put("model",  getDefaultModel());
                    payload.put("prompt", finalPrompt);
                    payload.put("stream", isStream);

                    JSONObject options = new JSONObject();
                    options.put("temperature", 0.1);
                    options.put("top_p", 0.9);
                    payload.put("options", options);

                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = payload.toString().getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    nextToken = readNextToken();
                } catch (Exception e) {
                    System.err.println("Ошибка инициализации stream: " + e.getMessage());
                    nextToken = null;
                }
            }
            private String readNextToken() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Проверяем отмену
                        if (isCancelled()) {
                            conn.disconnect();
                            return "[CANCELLED]";
                        }

                        line = line.trim();
                        if (!line.isEmpty()) {
                            JSONObject data = new JSONObject(line);
                            if (data.has("response")) {
                                return data.getString("response");
                            }
                            if (data.optBoolean("done", false)) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка чтения токена: " + e.getMessage());
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return nextToken != null && !"[CANCELLED]".equals(nextToken);
            }

            @Override
            public String next() {
                String current = nextToken;
                nextToken = readNextToken();
                if (current == null) {
                    throw new NoSuchElementException();
                }
                return current;
            }

            public void close() {
                try {
                    if (reader != null) reader.close();
                    if (conn != null) conn.disconnect();
                } catch (IOException e) {
                    System.err.println("Ошибка закрытия stream: " + e.getMessage());
                }
            }
        };
    }
    public static class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }


}