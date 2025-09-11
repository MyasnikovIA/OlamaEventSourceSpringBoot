package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.miacomsoft.olamaeventsourcespringboot.model.Document;
import ru.miacomsoft.olamaeventsourcespringboot.model.Embedding;
import ru.miacomsoft.olamaeventsourcespringboot.service.DocumentService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/documents") // Добавлен базовый путь
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @PostMapping(value = "/{clientId}")

    public ResponseEntity<?> createDocument(
            @PathVariable String clientId,
            @RequestParam String content,
            @RequestParam(required = false) String metadata) {
        try {
            JSONObject metadataJson = metadata != null ? new JSONObject(metadata) : new JSONObject();

            // Используем только один метод сервиса
            Document document = documentService.createDocument(content, metadataJson, clientId);

            JSONObject sseData = new JSONObject();
            if (document.getId() != null) {
                sseData.put("content", "✓ Документ успешно добавлен с ID: " + document.getId() + "\r\r");
            } else {
                sseData.put("content", "✗ Ошибка при добавлении документа ");
            }
            sseData.put("clientId", clientId);

            return ResponseEntity.ok(document);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    new JSONObject().put("error", e.getMessage()).toString());
        }
    }

    @PostMapping("/{documentId}/embedding")
    public ResponseEntity<?> addEmbedding(
            @PathVariable Long documentId,
            @RequestBody String embeddingRequest) {

        try {
            JSONObject requestJson = new JSONObject(embeddingRequest);
            JSONArray embedding = requestJson.getJSONArray("embedding");
            Double norm = requestJson.optDouble("norm", 0.0);

            Embedding embeddingObj = documentService.addEmbedding(documentId, embedding, norm);
            return ResponseEntity.ok(embeddingObj);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    new JSONObject().put("error", e.getMessage()).toString());
        }
    }

    @GetMapping
    public List<Document> getAllDocuments() {
        return documentService.getAllDocuments();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocumentById(@PathVariable Long id) {
        Optional<Document> document = documentService.getDocumentById(id);
        return document.isPresent() ?
                ResponseEntity.ok(document.get()) :
                ResponseEntity.notFound().build();
    }

    @GetMapping("/{documentId}/embedding")
    public ResponseEntity<?> getEmbeddingByDocumentId(@PathVariable Long documentId) {
        Optional<Embedding> embedding = documentService.getEmbeddingByDocumentId(documentId);
        return embedding.isPresent() ?
                ResponseEntity.ok(embedding.get()) :
                ResponseEntity.notFound().build();
    }

    @GetMapping("/statistics")
    public String getStatistics() {
        return documentService.getStatistics().toString();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id) {
        try {
            documentService.deleteDocument(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    new JSONObject().put("error", e.getMessage()).toString());
        }
    }
}