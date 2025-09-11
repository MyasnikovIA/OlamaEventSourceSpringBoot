package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.miacomsoft.olamaeventsourcespringboot.model.ChatHistory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OllamaService {
    // Добавляем константу PROMPT_TEMPLATE в начале класса
    private static final String PROMPT_TEMPLATE = """
        Используй следующий контекст для ответа на вопрос. Отвечай сначала на основе предоставленного контекста. 
        Если в контексте нет информации для ответа, продолжай поиск в модели и помечай ответ "Локальной база:".
        
        Контекст:
        {context}
        
        Вопрос: {query}
        
        Ответ:""";

    private static final String OLLAMA_HOST = "http://192.168.15.6:11434";
    private static String MODEL_NAME = "llama3.2-vision:latest";
    private static String EMBEDDING_NAME = "all-minilm:22m";
    private Map<String, HttpClient> activeClients = new HashMap<>();

    private String PROMPT_CHAR = "";
    private String PROMPT_GENERATE = "";

    private final ChatHistoryService chatHistoryService;
    private HttpClient client;

    private final SseService sseService;
    private final DocumentService documentService;
    private final EmbeddingService embeddingService; // Добавляем EmbeddingService

    public OllamaService(SseService sseService, DocumentService documentService,
                         EmbeddingService embeddingService, ChatHistoryService chatHistoryService) {

        this.sseService = sseService;
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.client = HttpClient.newHttpClient();
        this.chatHistoryService = chatHistoryService;
    }

    private JSONObject createQuery(String clientId, String queryTxt) {
        JSONObject result = new JSONObject();
        result.put("model", MODEL_NAME);
        result.put("messages", new JSONArray());
        result.put("stream", true);

        // Загружаем историю из БД
        List<JSONObject> history = chatHistoryService.getChatHistoryAsList(clientId);
        for (JSONObject rec : history) {
            result.getJSONArray("messages").put(rec);
        }

        result.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", queryTxt));
        return result;
    }

    // Модифицируем метод sendChatQuery
    public void sendChatQuery(String clientId, String requestBody) {
        boolean isDocQuery = false;
        JSONObject userMessage;

        if (requestBody.trim().startsWith("{") && requestBody.trim().endsWith("}")) {
            try {
                userMessage = new JSONObject(requestBody);
            } catch (Exception e) {
                userMessage = createQuery(clientId, requestBody);
            }
        } else {
            userMessage = createQuery(clientId, requestBody);
        }

        List<JSONObject> history = chatHistoryService.getChatHistoryAsList(clientId);
        JSONArray historyInput = userMessage.getJSONArray("messages");
        JSONObject lastQuery = historyInput.getJSONObject(historyInput.length() - 1);

        JSONObject metadata = new JSONObject();
        metadata.put("source", "chat_upload");
        metadata.put("timestamp", System.currentTimeMillis());
        metadata.put("clientId", clientId);
        String systemPrompt = PROMPT_TEMPLATE;
        if (userMessage.has("system_prompt")) {
            systemPrompt = userMessage.getString("system_prompt");
            userMessage.remove("system_prompt");
            if (systemPrompt.length() > 0) {
                systemPrompt = PROMPT_TEMPLATE;
            }
        }
        if (userMessage.has("doc")) {
            isDocQuery= true;
            for (Object docOne: userMessage.getJSONArray("doc")) {
                int docId = documentService.addDocument((String) docOne, metadata);
                JSONObject sseData = new JSONObject();
                if (docId>0) {
                    sseData.put("content", "✓ Документ успешно добавлен с ID: " + docId+"\r\r");
                } else {
                    sseData.put("content", "✗ Ошибка при добавлении документа ");
                }
                sseData.put("clientId", clientId);
                sseService.sendEventToClient(clientId, "message", sseData);
            }
            userMessage.remove("doc");
        }

        String content = "";
        if (lastQuery.has("content")) {
            content = lastQuery.getString("content");
        }

        // Обработка добавления документа
        if (content.length() > 0 && content.startsWith("doc:")) {
            isDocQuery= true;
            String documentContent = content.substring(4).trim();
            if (documentContent.length()>0) {
                try {
                    int docId = documentService.addDocument(documentContent, metadata);
                    JSONObject responseMessage = new JSONObject();
                    responseMessage.put("role", "assistant");
                    responseMessage.put("content", "✓ Документ успешно добавлен с ID: " + docId);
                    JSONObject sseData = new JSONObject();
                    sseData.put("content", "✓ Документ успешно добавлен с ID: " + docId);
                    sseData.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "message", sseData);

                    JSONObject completeEvent = new JSONObject();
                    completeEvent.put("final_content", "✓ Документ успешно добавлен с ID: " + docId);
                    completeEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "complete", completeEvent);
                } catch (Exception e) {
                    JSONObject errorMessage = new JSONObject();
                    errorMessage.put("role", "assistant");
                    errorMessage.put("content", "✗ Ошибка при добавлении документа: " + e.getMessage());
                    JSONObject errorEvent = new JSONObject();
                    errorEvent.put("error", "Ошибка при добавлении документа: " + e.getMessage());
                    errorEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "error", errorEvent);
                }
                return;
            }
        }

        // RAG ФУНКЦИОНАЛЬНОСТЬ: Получаем контекст из базы знаний
        if (userMessage.has("embeddingModel")) {
            String embeddingModel = userMessage.getString("embeddingModel");
            userMessage.remove("embeddingModel");

            if (content.length() > 0) {
                // Получаем контекст из базы знаний
                String context = documentService.getContextForQuery(content, 3, 0.6);

                if (context != null && !context.trim().isEmpty()) {
                    // Используем контекст в промпте
                    String prompt = systemPrompt
                            .replace("{context}", context)
                            .replace("{query}", content);
                    lastQuery.put("content", prompt);
                } else {
                    // Если контекст не найден, добавляем метку для ответа из модели
                    String prompt = "Вопрос: " + content + "\n\nОтвет (информация будет получена из модели):";
                    lastQuery.put("content", prompt);
                }
            }
        }

        history.add(lastQuery);
        // Сохраняем пользовательское сообщение в БД
        JSONObject userMessageToSave = new JSONObject();
        userMessageToSave.put("role", "user");
        userMessageToSave.put("content", content);
        userMessageToSave.put("metadata", metadata);
        chatHistoryService.saveMessage(clientId, userMessageToSave);
        if (!isDocQuery) {
            StringBuilder fullResponse = new StringBuilder();
            sendOllamaRequest(clientId, userMessage.toString(), fullResponse, true);
        }
    }
    public void sendGenerateQuery(String clientId, String requestBody) {
        boolean isDocQuery = false;
        JSONObject data;
        if (!requestBody.trim().startsWith("{") || !requestBody.trim().endsWith("}")) {
            data = new JSONObject();
            data.put("prompt", requestBody);
        } else {
            data = new JSONObject(requestBody);
        }

        JSONObject metadata = new JSONObject();
        metadata.put("source", "chat_upload");
        metadata.put("timestamp", System.currentTimeMillis());
        metadata.put("clientId", clientId);

        if (data.has("doc")) {
            isDocQuery = true;
            for (Object docOne: data.getJSONArray("doc")) {
                int docId = documentService.addDocument((String) docOne, metadata);
                JSONObject sseData = new JSONObject();
                if (docId>0) {
                    sseData.put("content", "✓ Документ успешно добавлен с ID: " + docId+"\r\r");
                } else {
                    sseData.put("content", "✗ Ошибка при добавлении документа ");
                }
                sseData.put("clientId", clientId);
                sseService.sendEventToClient(clientId, "message", sseData);
            }
            data.remove("doc");
        }

        String content = "";
        if (data.has("prompt")) {
            content = data.getString("prompt");
        }

        // Обработка добавления документа
        if (content.length() > 0 && content.startsWith("doc:")) {
            isDocQuery = true;
            String documentContent = content.substring(4).trim();
            if (documentContent.length()>0) {
                try {
                    int docId = documentService.addDocument(documentContent, metadata);
                    JSONObject responseData = new JSONObject();
                    responseData.put("response", "✓ Документ успешно добавлен с ID: " + docId);
                    responseData.put("done", true);

                    JSONObject sseData = new JSONObject();
                    sseData.put("content", "✓ Документ успешно добавлен с ID: " + docId);
                    sseData.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "message", sseData);

                    JSONObject completeEvent = new JSONObject();
                    completeEvent.put("final_content", "✓ Документ успешно добавлен с ID: " + docId);
                    completeEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "complete", completeEvent);
                } catch (Exception e) {
                    JSONObject errorEvent = new JSONObject();
                    errorEvent.put("error", "Ошибка при добавлении документа: " + e.getMessage());
                    errorEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "error", errorEvent);
                }
                JSONObject sseDataok = new JSONObject();
                sseDataok.put("content", " Обработка документов закончена ");
                sseDataok.put("clientId", clientId);
                sseService.sendEventToClient(clientId, "message", sseDataok);
            }
            return;
        }
        String systemPrompt = PROMPT_TEMPLATE;
        if (data.has("system_prompt")) {
            systemPrompt = data.getString("system_prompt");
            data.remove("system_prompt");
            if (systemPrompt.length() > 0) {
                systemPrompt = PROMPT_TEMPLATE;
            }
        }
        // RAG ФУНКЦИОНАЛЬНОСТЬ: Получаем контекст из базы знаний
        if (data.has("embeddingModel")) {
            String embeddingModel = data.getString("embeddingModel");
            data.remove("embeddingModel");

            if (content.length() > 0) {
                // Получаем контекст из базы знаний
                String context = documentService.getContextForQuery(content, 3, 0.6);

                if (context != null && !context.trim().isEmpty()) {
                    // Используем контекст в промпте
                    String prompt = systemPrompt
                            .replace("{context}", context)
                            .replace("{query}", content);
                    data.put("prompt", prompt);
                } else {
                    // Если контекст не найден, добавляем метку для ответа из модели
                    String prompt = "Вопрос: " + content + "\n\nОтвет (информация будет получена из модели):";
                    data.put("prompt", prompt);
                }
            }
        }



        StringBuilder fullResponse = new StringBuilder();
        JSONObject requestJson = new JSONObject();
        requestJson.put("model", MODEL_NAME);
        requestJson.put("prompt", data.getString("prompt"));
        requestJson.put("stream", true);
        if (!isDocQuery) {
            sendOllamaRequest(clientId, requestJson.toString(), fullResponse, false);
        }
    }

    // Добавляем метод в класс OllamaService
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

    private void sendOllamaRequest(String clientId, String requestBody, StringBuilder fullResponse, boolean isChat) {
        HttpClient client = HttpClient.newHttpClient();
        activeClients.put(clientId, client);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_HOST + (isChat ? "/api/chat" : "/api/generate")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenApply(HttpResponse::body)
                .thenAccept(stream -> {
                    JSONObject startEvent = new JSONObject();
                    startEvent.put("type", "start");
                    sseService.sendEventToClient(clientId, "start", startEvent);

                    stream.forEach(line -> {
                        if (!line.trim().isEmpty()) {
                            try {
                                JSONObject response = new JSONObject(line);
                                String content = "";

                                if (isChat && response.has("message")) {
                                    JSONObject message = response.getJSONObject("message");
                                    if (message.has("content")) {
                                        content = message.getString("content");
                                    }
                                } else if (!isChat && response.has("response")) {
                                    content = response.getString("response");
                                }

                                System.out.print(content);
                                fullResponse.append(content);

                                JSONObject sseData = new JSONObject();
                                sseData.put("content", content);
                                sseData.put("clientId", clientId);
                                sseService.sendEventToClient(clientId, "message", sseData);

                            } catch (Exception e) {
                                System.err.println("Error parsing response: " + e.getMessage());
                            }
                        }
                    });
                })
                .thenRun(() -> {
                    activeClients.remove(clientId);

                    JSONObject completeEvent = new JSONObject();
                    completeEvent.put("final_content", fullResponse.toString());
                    completeEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "complete", completeEvent);

                    if (isChat) {
                        // Сохраняем ответ ассистента в БД
                        JSONObject assistantMessage = new JSONObject();
                        assistantMessage.put("role", "assistant");
                        assistantMessage.put("content", fullResponse.toString());
                        assistantMessage.put("metadata", new JSONObject().put("model", MODEL_NAME));

                        chatHistoryService.saveMessage(clientId, assistantMessage);
                    }
                })
                .exceptionally(e -> {
                    activeClients.remove(clientId);
                    System.err.println("Error: " + e.getMessage());
                    JSONObject errorEvent = new JSONObject();
                    errorEvent.put("error", e.getMessage());
                    errorEvent.put("clientId", clientId);
                    sseService.sendEventToClient(clientId, "error", errorEvent);
                    return null;
                });
    }

    public JSONArray getChatHistory(String clientId) {
        return chatHistoryService.getChatHistory(clientId);
    }

    public void clearChatHistory(String clientId) {
        chatHistoryService.clearChatHistory(clientId);
    }

    public JSONArray getAvailableModelsWithDetails() {
        JSONArray models = new JSONArray();
        try {
            URI uri = new URI(String.format("%s/api/tags", OLLAMA_HOST));
            URL url = uri.toURL();
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
                        JSONObject modelInfo = new JSONObject();
                        String modelName = model.getString("name");
                        modelInfo.put("name", modelName);
                        modelInfo.put("size", model.optLong("size", 0));
                        modelInfo.put("modified", model.optString("modified_at", ""));
                        modelInfo.put("supportsImages", isVisionModel(modelName));
                        modelInfo.put("isEmbeddingModel", isEmbeddingModel(modelName));
                        models.put(modelInfo);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения списка моделей: " + e.getMessage());
        }
        return models;
    }

    private static JSONObject getModelInfo(String modelName) {
        try {
            URI uri = new URI(String.format("%s/api/show", OLLAMA_HOST));
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String jsonInputString = String.format("{\"name\": \"%s\"}", modelName);
            conn.getOutputStream().write(jsonInputString.getBytes("utf-8"));

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String response = br.lines().collect(Collectors.joining());
                    return new JSONObject(response);
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения информации о модели " + modelName + ": " + e.getMessage());
        }
        return null;
    }

    public void cancelGeneration(String clientId) {
        HttpClient client = activeClients.get(clientId);
        if (client != null) {
            try {
                activeClients.remove(clientId);
                JSONObject cancelEvent = new JSONObject();
                cancelEvent.put("cancelled", true);
                cancelEvent.put("clientId", clientId);
                sseService.sendEventToClient(clientId, "cancelled", cancelEvent);
                System.out.println("Генерация отменена для клиента: " + clientId);
            } catch (Exception e) {
                System.err.println("Ошибка при отмене генерации: " + e.getMessage());
            }
        }
    }

    public static boolean isVisionModel(String modelName) {
        JSONObject modelInfo = getModelInfo(modelName);
        if (modelInfo == null) {
            return false;
        }

        if (modelInfo.has("capabilities")) {
            JSONArray parameters = modelInfo.optJSONArray("capabilities", new JSONArray());
            boolean res = false;
            for (int i = 0; i < parameters.length(); i++) {
                String param = parameters.getString(i).toLowerCase();
                if (param.equals("vision")) {
                    res = true;
                    break;
                }
            }
            return res;
        }

        if (modelInfo.has("parameters")) {
            String parameters = modelInfo.optString("parameters", "").toLowerCase();
            if (parameters.contains("vision") || parameters.contains("multimodal") ||
                    parameters.contains("image") || parameters.contains("llava")) {
                return true;
            }
        }

        if (modelInfo.has("details") && modelInfo.getJSONObject("details").has("tags")) {
            JSONArray tags = modelInfo.getJSONObject("details").optJSONArray("tags");
            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) {
                    String tag = tags.getString(i).toLowerCase();
                    if (tag.contains("vision") || tag.contains("multimodal") ||
                            tag.contains("image") || tag.contains("visual")) {
                        return true;
                    }
                }
            }
        }

        if (modelInfo.has("template")) {
            String template = modelInfo.optString("template", "").toLowerCase();
            if (template.contains("image") || template.contains("vision") ||
                    template.contains("<image>") || template.contains("[img]")) {
                return true;
            }
        }

        return false;
    }

    public String getOllamaHost() {
        return OLLAMA_HOST;
    }

    public String getPROMPT_CHAR() {
        return PROMPT_CHAR;
    }

    public void setPROMPT_CHAR(String PROMPT_CHAR) {
        this.PROMPT_CHAR = PROMPT_CHAR;
    }

    public String getPROMPT_GENERATE() {
        return PROMPT_GENERATE;
    }

    public void setPROMPT_GENERATE(String PROMPT_GENERATE) {
        this.PROMPT_GENERATE = PROMPT_GENERATE;
    }

    public String getModelName() {
        return MODEL_NAME;
    }

    public String getEmbeddingName() {
        return EMBEDDING_NAME;
    }

    public void setEmbeddingName(String modelName) {
        EMBEDDING_NAME = modelName;
    }

    public void setModelName(String modelName) {
        MODEL_NAME = modelName;
    }

    public static boolean isEmbeddingModel(String modelName) {
        JSONObject modelInfo = getModelInfo(modelName);
        if (modelInfo == null) {
            return false;
        }

        String lowerModelName = modelName.toLowerCase();
        if (lowerModelName.contains("embed") ||
                lowerModelName.contains("nomic-embed") ||
                lowerModelName.contains("all-minilm") ||
                lowerModelName.contains("bge") ||
                lowerModelName.contains("e5")) {
            return true;
        }

        if (modelInfo.has("size")) {
            long size = modelInfo.getLong("size");
            if (size < 1024 * 1024 * 1024) {
                return true;
            }
        }

        if (modelInfo.has("parameters")) {
            String parameters = modelInfo.optString("parameters", "").toLowerCase();
            if (parameters.contains("embed") || parameters.contains("dimension")) {
                return true;
            }
        }

        return false;
    }

    public DocumentService getDocumentService() {
        return documentService;
    }
}