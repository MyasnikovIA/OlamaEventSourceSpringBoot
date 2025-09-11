package ru.miacomsoft.oracle_bd.rag;

import ru.miacomsoft.oracle_bd.rag.utils.SpeakToText;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class LmStudioRagClient {
    private final PostgresDatabase database;
    private final OllamaClient ollamaClient;
    private final Properties properties;
    private List<OllamaClient.ChatMessage> chatHistory = new ArrayList<>();
    private String currentChatId;
    private boolean streamResponse = false;
    // Добавить поле для промта generate
    private String generatePrompt = "Ты - помощник, который генерирует текст на основе промта. Отвечай всегда на русском языке.";

    private String chatPrompt = "Ты - помощник, который отвечает на вопросы. Отвечай всегда на русском языке. Если в базе знаний есть информация по вопросу, используй её. Если информации нет, скажи об этом.";
    //             Если в контексте нет информации для ответа, скажи "В предоставленных материалах нет информации для ответа на этот вопрос".
    private String promptGenerate = """
            Используй следующий контекст для ответа на вопрос. Если ответ не найден в контексте, тогда искать ответ в модели и пометить "Ответ из модели:". 
            
            Контекст:
            {context}
            
            Вопрос: {query}
            
            Ответ:""";

    // Добавляем поле для отслеживания активных запросов
    private final Map<String, AtomicBoolean> activeRequests = new ConcurrentHashMap<>();

    public LmStudioRagClient(Properties properties) throws SQLException {
        this.properties = properties;
        this.database = new PostgresDatabase(properties);
        this.ollamaClient = new OllamaClient(properties);
        this.streamResponse = Boolean.parseBoolean(properties.getProperty("ollama.stream", "false"));
        this.currentChatId = properties.getProperty("ollama.chatId", "");
        startNewChat();
    }

    public void startNewChat() throws SQLException {
        if (this.currentChatId.length() == 0) {
            this.currentChatId = UUID.randomUUID().toString();
        }
        loadChatHistory();
    }

    private void loadChatHistory() throws SQLException {
        chatHistory.clear();
        List<PostgresDatabase.ChatMessage> dbHistory = database.getChatHistory(currentChatId);
        for (PostgresDatabase.ChatMessage msg : dbHistory) {
            chatHistory.add(new OllamaClient.ChatMessage(msg.role, msg.content));
        }
    }

    public void initializeDocuments(List<String> documents) throws IOException, SQLException {
        for (String doc : documents) {
            // 1. Проверка на полный дубликат
            if (isExactDuplicate(doc)) {
                System.out.println("⚠️  Пропускаем полный дубликат документа: " + getDocumentPreview(doc));
                continue;
            }

            // 2. Получение эмбеддинга для документа
            List<Double> embedding = ollamaClient.getEmbedding(doc);

            // 3. Проверка на семантический дубликат с использованием новой функции
            double maxSimilarity = database.getMaxSimilarityPercent(embedding);

            if (maxSimilarity >= 99.0) { // 99% сходство
                System.out.println("⚠️  Пропускаем семантический дубликат (" + String.format("%.2f", maxSimilarity) + "% сходство): " + getDocumentPreview(doc));
                continue;
            }

            // 4. Сохранение документа, если он уникальный
            database.storeDocumentWithEmbedding(doc, embedding);
            System.out.println("✅ Сохранен уникальный документ: " + getDocumentPreview(doc) + " (макс. сходство: " + String.format("%.2f", maxSimilarity) + "%)");
        }
    }

    private boolean isExactDuplicate(String content) throws SQLException {
        // Проверяем существование точного дубликата в базе данных
        String sql = "SELECT COUNT(*) FROM documents WHERE content = ?";
        try (PreparedStatement pstmt = database.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, content);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    public void setGeneratePrompt(String generatePrompt) {
        this.generatePrompt = generatePrompt;
    }

    private double calculateCosineSimilarity(List<Double> embedding1, List<Double> embedding2) {
        if (embedding1.size() != embedding2.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < embedding1.size(); i++) {
            dotProduct += embedding1.get(i) * embedding2.get(i);
            norm1 += Math.pow(embedding1.get(i), 2);
            norm2 += Math.pow(embedding2.get(i), 2);
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private String getDocumentPreview(String content) {
        if (content.length() <= 50) {
            return content;
        }
        return content.substring(0, 47) + "...";
    }

    public String ragQuery(String question) throws IOException, SQLException {
        return ragQuery(question, null, null);
    }

    public String ragQuery(String question, Consumer<String> streamConsumer) throws IOException, SQLException {
        return ragQuery(question, streamConsumer, null);
    }

    public String ragQuery(String question, Consumer<String> streamConsumer, Consumer<String> audioConsumer) throws IOException, SQLException {
        String requestId = UUID.randomUUID().toString();
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        activeRequests.put(requestId, isCancelled);

        try {
            List<Double> questionEmbedding = ollamaClient.getEmbedding(question);

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            List<PostgresDatabase.Document> similarDocs = database.findSimilarDocuments(questionEmbedding, 2);

            if (similarDocs.isEmpty()) {
                String response = "Извините, я не нашел информации по вашему вопросу в моей базе знаний.";
                addToChatHistory("user", question);
                addToChatHistory("assistant", response);
                return response;
            }

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            String context = buildContext(similarDocs);
            String answer = callOllamaApi(question, context, streamConsumer, audioConsumer, isCancelled);

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            addToChatHistory("user", question);
            addToChatHistory("assistant", answer);
            return answer;
        } finally {
            activeRequests.remove(requestId);
        }
    }

    public String ragQueryGenerate(String question, Consumer<String> streamConsumer) throws IOException, SQLException {
        return ragQueryGenerate(question, streamConsumer, null);
    }

    public String ragQueryGenerate(String question, Consumer<String> streamConsumer, Consumer<String> audioConsumer) throws IOException, SQLException {
        String requestId = UUID.randomUUID().toString();
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        activeRequests.put(requestId, isCancelled);

        try {
            List<Double> questionEmbedding = ollamaClient.getEmbedding(question);

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            List<PostgresDatabase.Document> similarDocs = database.findSimilarDocuments(questionEmbedding, 2);

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            if (similarDocs.isEmpty()) {
                String response = "Извините, я не нашел информации по вашему вопросу в моей базе знаний.";
                addToChatHistory("user", question);
                addToChatHistory("assistant", response);
                return response;
            }

            // Проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            StringBuilder fullResponse = new StringBuilder();
            Iterator<String> responseStream;
            if (streamConsumer != null) {
                responseStream = ollamaClient.generateResponseStream(question, buildContext(similarDocs), promptGenerate, true);
            } else {
                responseStream = ollamaClient.generateResponseStream(question, buildContext(similarDocs), promptGenerate, false);
            }

            SpeakToText speakToText = new SpeakToText(); // Создаем локальный экземпляр

            while (responseStream.hasNext()) {
                // Проверка отмены на каждой итерации
                if (isCancelled.get()) {
                    return "[CANCELLED]";
                }

                String token = responseStream.next();
                if (streamConsumer != null) {
                    streamConsumer.accept(token);
                }

                // Отправка аудио, если есть аудио-консьюмер
                if (audioConsumer != null && !token.trim().isEmpty()) {
                    speakToText.speakStreamFile(token, currentChatId, audioConsumer);
                }

                fullResponse.append(token);
            }

            // Финальная проверка отмены
            if (isCancelled.get()) {
                return "[CANCELLED]";
            }

            return fullResponse.toString();
        } finally {
            activeRequests.remove(requestId);
        }
    }

    // Метод для отмены активных запросов
    public void cancelAllRequests() {
        for (AtomicBoolean isCancelled : activeRequests.values()) {
            isCancelled.set(true);
        }
        activeRequests.clear();
    }

    // Метод для отмены конкретного запроса по chatId
    public void cancelRequest(String chatId) {
        // В этой реализации отменяем все запросы, так как у нас нет привязки к конкретному chatId
        cancelAllRequests();
    }

    private void addToChatHistory(String role, String content) throws SQLException {
        chatHistory.add(new OllamaClient.ChatMessage(role, content));
        database.addChatMessage(currentChatId, role, content);
    }

    private String buildContext(List<PostgresDatabase.Document> documents) {
        StringBuilder context = new StringBuilder();
        for (PostgresDatabase.Document doc : documents) {
            context.append(doc.content).append("\n");
        }
        return context.toString();
    }

    private String callOllamaApi(String question, String context, Consumer<String> streamConsumer, Consumer<String> audioConsumer, AtomicBoolean isCancelled) throws IOException {
        SpeakToText speakToText = new SpeakToText(); // Создаем локальный экземпляр

        List<OllamaClient.ChatMessage> messages = new ArrayList<>();
        String prompt = chatPrompt;
        if (prompt.contains("{context}")) {
            prompt = chatPrompt.replace("{context}", context);
        } else {
            prompt += "\n Контекст для ответа:\n" + context;
        }
        // Системное сообщение с контекстом
        messages.add(new OllamaClient.ChatMessage("system", prompt));
        // История чата
        messages.addAll(chatHistory);
        // Текущий вопрос пользователя
        messages.add(new OllamaClient.ChatMessage("user", question));

        // Передаем флаг отмены в OllamaClient
        ollamaClient.setCancellationFlag(isCancelled);

        try {
            return ollamaClient.generateResponse(
                    ollamaClient.getDefaultModel(),
                    messages,
                    streamResponse || streamConsumer != null,
                    fragment -> {
                        // Проверяем отмену перед отправкой каждого фрагмента
                        if (!isCancelled.get()) {
                            if (streamConsumer != null) {
                                streamConsumer.accept(fragment);
                            }
                            // Отправка аудио, если есть аудио-консьюмер
                            if (audioConsumer != null && !fragment.trim().isEmpty() && !fragment.equals("[DONE]")) {
                                speakToText.speakStreamFile(fragment, currentChatId, audioConsumer);
                            }
                        }
                    }
            );
        } finally {
            ollamaClient.clearCancellationFlag();
        }
    }

    public void close() throws SQLException {
        if (database != null) {
            database.close();
        }
    }
    private boolean inCodeBlock = false;

    public void console() throws SQLException, IOException {
        System.out.println("🤖 Ollama RAG Chat Console");
        System.out.println("Введите 'quit' для выхода");
        System.out.println("Введите 'clr' для очистки истории");
        System.out.println("Введите 'doc: ваш текст' для добавления документа");
        System.out.println("Введите 'models' для просмотра моделей");
        System.out.println("Введите 'model:ИМЯ_МОДЕЛИ' для выбора моделей диалога");
        System.out.println("Введите 'model_emb:ИМЯ_МОДЕЛИ' для выбора моделей создания embeddings");
        System.out.println("Введите 'prompt: ваш промт' для изменения промта");
        System.out.println("Введите 'prompt' показать действующий промта");
        System.out.println("Введите 'voice on/off' для управления озвучиванием");
        System.out.println("Введите 'pull:ИМЯ_МОДЕЛИ' для загрузки модели");
        System.out.println("Введите 'delete:ИМЯ_МОДЕЛИ' для удаления модели");
        System.out.println("Для отправки сообщения нажмите Enter");
        System.out.println("=".repeat(50));

        SpeakToText speakToText = new SpeakToText();
        StringBuilder inputBuffer = new StringBuilder();
        boolean speechEnabled = false;
        Scanner scanner = new Scanner(System.in);

        try {
            while (true) {
                System.out.print("Вы: ");
                String userInput = scanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("quit")) {
                    break;
                } else if (userInput.equalsIgnoreCase("models")) {
                    List<Map<String, Object>> models = ollamaClient.getAvailableModelsWithDetails();
                    if (models.isEmpty()) {
                        System.out.println("Не найдено доступных моделей Ollama");
                        continue;
                    }

                    String currentModel = ollamaClient.getDefaultModel();
                    System.out.println("\nДоступные модели Ollama:");
                    for (int i = 0; i < models.size(); i++) {
                        Map<String, Object> model = models.get(i);
                        String name = (String) model.get("name");
                        long sizeBytes = (Long) model.get("size");
                        String sizeFormatted = ollamaClient.formatFileSize(sizeBytes);

                        String marker = name.equals(currentModel) ? " *" : "";
                        System.out.printf("%d. %s%s (%s)%n", i + 1, name, marker, sizeFormatted);
                    }

                    // Предложение выбрать модель по номеру
                    System.out.print("\nВведите номер модели для активации (или 0 для отмены): ");
                    try {
                        int choice = Integer.parseInt(scanner.nextLine().trim());
                        if (choice > 0 && choice <= models.size()) {
                            String selectedModel = (String) models.get(choice - 1).get("name");
                            ollamaClient.setDefaultModel(selectedModel);
                            System.out.println("Модель '" + selectedModel + "' активирована");
                        } else if (choice != 0) {
                            System.out.println("Неверный номер модели");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Введите корректный номер");
                    }
                    continue;
                } else if (userInput.equalsIgnoreCase("voice on")) {
                    speechEnabled = true;
                    System.out.println("\nОзвучивание включено");
                    continue;
                } else if (userInput.equalsIgnoreCase("voice off")) {
                    speechEnabled = false;
                    System.out.println("\nОзвучивание выключено");
                    continue;
                } else if (userInput.startsWith("prompt:")) {
                    String prompt = userInput.substring("prompt:".length()).trim();
                    setChatPrompt(prompt);
                    System.out.println("Промт установлен: " + prompt);
                    continue;
                } else if (userInput.equals("prompt")) {
                    System.out.println("Активный промт:\n " + getChatPrompt());
                    continue;
                } else if (userInput.startsWith("model:")) {
                    String model = userInput.substring("model:".length()).trim();
                    ollamaClient.setDefaultModel(model);
                    System.out.println("Модель диалога установлена: " + model);
                    continue;
                } else if (userInput.startsWith("model_emb:")) {
                    String model = userInput.substring("model_emb:".length()).trim();
                    ollamaClient.setEmbeddingModel(model);
                    System.out.println("Модель эмбеддингов установлена: " + model);
                    continue;
                } else if (userInput.startsWith("doc:")) {
                    String document = userInput.substring("doc:".length()).trim();
                    if (!document.isEmpty()) {
                        List<String> documents = Arrays.asList(document);
                        initializeDocuments(documents);
                    }
                    continue;
                } else if (userInput.startsWith("pull:")) {
                    String modelName = userInput.substring("pull:".length()).trim();
                    if (!modelName.isEmpty()) {
                        System.out.println("Загрузка модели: " + modelName);
                        try {
                            ollamaClient.pullModel(modelName, status -> {
                                System.out.print("\rПрогресс: " + status);
                            });
                            System.out.println("\nМодель '" + modelName + "' успешно загружена");
                        } catch (IOException e) {
                            System.out.println("Ошибка загрузки модели: " + e.getMessage());
                        }
                    }
                    continue;
                } else if (userInput.startsWith("delete:")) {
                    String modelName = userInput.substring("delete:".length()).trim();
                    if (!modelName.isEmpty()) {
                        System.out.print("Вы уверены, что хотите удалить модель '" + modelName + "'? (y/N): ");
                        String confirmation = scanner.nextLine().trim();
                        if (confirmation.equalsIgnoreCase("y")) {
                            try {
                                ollamaClient.deleteModel(modelName);
                                System.out.println("Модель '" + modelName + "' удалена");
                            } catch (IOException e) {
                                System.out.println("Ошибка удаления модели: " + e.getMessage());
                            }
                        } else {
                            System.out.println("Удаление отменено");
                        }
                    }
                    continue;
                } else if (userInput.equalsIgnoreCase("clr")) {
                    clearChatHistory();
                    System.out.println("История очищена");
                    continue;
                }

                if (!userInput.isEmpty()) {
                    boolean finalSpeechEnabled = speechEnabled;
                    AtomicBoolean skipSpeechEnabled = new AtomicBoolean(false);
                    StringBuilder codeBlockBuffer = new StringBuilder();

                    ragQuery(userInput, fragmentText -> {
                        if (!"[DONE]".equals(fragmentText)) {
                            System.out.print(fragmentText);
                        } else {
                            System.out.println("");
                        }
                        if (finalSpeechEnabled) {
                            speakToText.speakStream(fragmentText);
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }

        System.out.println("До свидания!");
    }

    public void setConnectTimeout(int timeout, TimeUnit unit) {
        ollamaClient.setConnectTimeout(timeout, unit);
    }

    public void setReadTimeout(int timeout, TimeUnit unit) {
        ollamaClient.setReadTimeout(timeout, unit);
    }

    public String getChatPrompt() {
        return chatPrompt;
    }

    public void setChatPrompt(String prompt) {
        this.chatPrompt = prompt;
    }

    public String getCurrentChatId() {
        return currentChatId;
    }

    public void setStreamResponse(boolean streamResponse) {
        this.streamResponse = streamResponse;
    }

    public OllamaClient getOllamaClient() {
        return ollamaClient;
    }
    public PostgresDatabase getDataBase() {
        return database;
    }

    public void clearChatHistory() throws SQLException {
        // clearChatHistory(currentChatId);
    }
    // Добавить этот метод в класс LmStudioRagClient
    public void setCurrentChatId(String chatId) throws SQLException {
        this.currentChatId = chatId;
        loadChatHistory(); // Перезагружаем историю для нового chatId
    }
    public String getGeneratePrompt() {
        return promptGenerate;
        //return generatePrompt;
    }
}