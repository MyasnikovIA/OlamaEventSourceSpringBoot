package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.miacomsoft.olamaeventsourcespringboot.model.Document;
import ru.miacomsoft.olamaeventsourcespringboot.model.Embedding;
import ru.miacomsoft.olamaeventsourcespringboot.repository.DocumentRepository;
import ru.miacomsoft.olamaeventsourcespringboot.repository.EmbeddingRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EmbeddingRepository embeddingRepository;
    @Autowired
    private EmbeddingService embeddingService;

    private final OllamaService ollamaService;

    @Autowired
    public DocumentService(@Lazy OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @Transactional
    public int addDocument(String content, JSONObject metadata) {
        try {
            List<Object[]> similarDocs = searchSimilar(content, 1, 0.9);
            if (!similarDocs.isEmpty()) {
                double maxSimilarity = (Double) similarDocs.get(0)[3];
                System.out.printf("Документ уже существует (схожесть: %.3f). Пропускаем добавление.%n", maxSimilarity);
                return -1;
            }

            Document document = new Document(content, metadata);
            Document savedDocument = documentRepository.save(document);

            List<Double> embedding = embeddingService.getEmbeddings(content);
            double embeddingNorm = calculateEmbeddingNorm(embedding);
            JSONArray embeddingJson = new JSONArray(embedding);

            Embedding embeddingObj = new Embedding(savedDocument, embeddingJson, embeddingNorm);
            embeddingRepository.save(embeddingObj);

            System.out.println("Документ добавлен с ID: " + savedDocument.getId());
            return savedDocument.getId().intValue();

        } catch (Exception e) {
            System.err.println("Ошибка при добавлении документа: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // Новый метод с clientId
    @Transactional
    public Document createDocument(String content, JSONObject metadata, String clientId) {
        try {
            // Добавляем clientId в метаданные
            if (metadata == null) {
                metadata = new JSONObject();
            }
            metadata.put("clientId", clientId);

            List<Object[]> similarDocs = searchSimilar(content, 1, 0.9);
            if (!similarDocs.isEmpty()) {
                double maxSimilarity = (Double) similarDocs.get(0)[3];
                System.out.printf("Документ уже существует (схожесть: %.3f). Пропускаем добавление.%n", maxSimilarity);
                throw new RuntimeException("Документ уже существует (схожесть: " + maxSimilarity + ")");
            }

            Document document = new Document(content, metadata);
            Document savedDocument = documentRepository.save(document);

            List<Double> embedding = embeddingService.getEmbeddings(content);
            double embeddingNorm = calculateEmbeddingNorm(embedding);
            JSONArray embeddingJson = new JSONArray(embedding);

            Embedding embeddingObj = new Embedding(savedDocument, embeddingJson, embeddingNorm);
            embeddingRepository.save(embeddingObj);

            System.out.println("Документ добавлен с ID: " + savedDocument.getId() + " для клиента: " + clientId);
            return savedDocument;

        } catch (Exception e) {
            System.err.println("Ошибка при создании документа: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<Object[]> searchSimilar(String query, int topK, double threshold) {
        try {
            List<Double> queryEmbedding = embeddingService.getEmbeddings(query);
            JSONArray queryEmbeddingJson = new JSONArray(queryEmbedding);
            String queryEmbeddingStr = queryEmbeddingJson.toString();

            List<Object[]> results = embeddingRepository.findSimilarDocuments(
                    queryEmbeddingStr, topK, threshold);

            return results;
        } catch (Exception e) {
            System.err.println("Ошибка поиска: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public String getContextForQuery(String query, int topK, double threshold) {
        try {
            List<Object[]> similarDocs = searchSimilar(query, topK, threshold);

            if (similarDocs.isEmpty()) {
                return null; // Контекст не найден
            }

            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("Контекст из базы знаний:\n\n");

            for (Object[] doc : similarDocs) {
                Long docId = (Long) doc[0];
                String content = (String) doc[1];
                String metadata = (String) doc[2];
                Double similarity = (Double) doc[3];

                contextBuilder.append(String.format("Документ ID: %d (схожесть: %.3f)\n", docId, similarity));
                contextBuilder.append("Содержимое: ").append(content).append("\n");

                if (metadata != null && !metadata.trim().isEmpty()) {
                    contextBuilder.append("Метаданные: ").append(metadata).append("\n");
                }

                contextBuilder.append("---\n\n");
            }

            return contextBuilder.toString();
        } catch (Exception e) {
            System.err.println("Ошибка получения контекста: " + e.getMessage());
            return null;
        }
    }

    private double calculateEmbeddingNorm(List<Double> embedding) {
        return Math.sqrt(embedding.stream()
                .mapToDouble(d -> d * d)
                .sum());
    }

    // Старый метод без clientId (оставляем для обратной совместимости)
    @Transactional
    public Document createDocument(String content, JSONObject metadata) {
        return createDocument(content, metadata, "default");
    }

    @Transactional
    public Embedding addEmbedding(Long documentId, JSONArray embedding, Double norm) {
        Optional<Document> documentOpt = documentRepository.findById(documentId);
        if (documentOpt.isEmpty()) {
            throw new RuntimeException("Document not found with id: " + documentId);
        }

        if (embeddingRepository.existsByDocumentId(documentId)) {
            throw new RuntimeException("Embedding already exists for document id: " + documentId);
        }

        Embedding embeddingObj = new Embedding(documentOpt.get(), embedding, norm);
        return embeddingRepository.save(embeddingObj);
    }

    public List<Document> getAllDocuments() {
        return documentRepository.findAll();
    }

    public Optional<Document> getDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    public Optional<Embedding> getEmbeddingByDocumentId(Long documentId) {
        return embeddingRepository.findAll().stream().filter(e -> e.getDocument().getId().equals(documentId)).findFirst();
    }

    public JSONObject getStatistics() {
        JSONObject stats = new JSONObject();
        stats.put("total_documents", documentRepository.countDocuments());
        stats.put("total_embeddings", embeddingRepository.countEmbeddings());
        stats.put("average_embedding_norm", embeddingRepository.averageEmbeddingNorm());
        return stats;
    }

    @Transactional
    public void deleteDocument(Long id) {
        documentRepository.deleteById(id);
    }
}