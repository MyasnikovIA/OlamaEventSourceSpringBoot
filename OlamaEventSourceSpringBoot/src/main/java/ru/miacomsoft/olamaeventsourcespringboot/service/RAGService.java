package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.miacomsoft.olamaeventsourcespringboot.repository.EmbeddingRepository;

import java.util.List;

@Service
public class RAGService {

    @Autowired
    private EmbeddingRepository embeddingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private DocumentService documentService;

    /**
     * Получает релевантный контекст для запроса используя RAG
     */
    public String getRelevantContext(String query, int topK, double similarityThreshold) {
        try {
            // Получаем эмбеддинг запроса
            List<Double> queryEmbedding = embeddingService.getEmbeddings(query);
            JSONArray queryEmbeddingJson = new JSONArray(queryEmbedding);
            String queryEmbeddingStr = queryEmbeddingJson.toString();

            // Ищем похожие документы в базе
            List<Object[]> similarDocuments = embeddingRepository.findSimilarDocuments(
                    queryEmbeddingStr, topK, similarityThreshold);

            if (similarDocuments.isEmpty()) {
                return null; // Контекст не найден
            }

            // Формируем контекст из найденных документов
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("Релевантный контекст из базы знаний:\n\n");

            for (Object[] doc : similarDocuments) {
                Long documentId = (Long) doc[0];
                String content = (String) doc[1];
                String metadata = (String) doc[2];
                Double similarity = (Double) doc[3];

                contextBuilder.append(String.format("=== Документ ID: %d (схожесть: %.3f) ===\n",
                        documentId, similarity));
                contextBuilder.append(content).append("\n\n");

                if (metadata != null && !metadata.trim().isEmpty() && !metadata.equals("{}")) {
                    contextBuilder.append("Метаданные: ").append(metadata).append("\n");
                }

                contextBuilder.append("---\n\n");
            }

            return contextBuilder.toString();

        } catch (Exception e) {
            System.err.println("Ошибка при получении RAG контекста: " + e.getMessage());
            return null;
        }
    }

    /**
     * Создает промпт для LLM с RAG контекстом
     */
    public String createRAGPrompt(String query, String context) {
        if (context == null || context.trim().isEmpty()) {
            // Если контекст не найден, возвращаем обычный промпт
            return "Вопрос: " + query + "\n\nОтвет (информация будет получена из модели):";
        }

        return "Используй следующий контекст для ответа на вопрос. " +
                "Отвечай строго на основе предоставленного контекста. " +
                "Если в контексте нет информации для ответа, скажи об этом и продолжай поиск в своей базе знаний.\n\n" +
                "Контекст:\n" + context + "\n\n" +
                "Вопрос: " + query + "\n\n" +
                "Ответ:";
    }
}