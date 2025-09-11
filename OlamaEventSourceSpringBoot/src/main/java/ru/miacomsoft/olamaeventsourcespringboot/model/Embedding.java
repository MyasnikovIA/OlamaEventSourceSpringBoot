package ru.miacomsoft.olamaeventsourcespringboot.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.json.JSONArray;

import java.time.LocalDateTime;

@Entity
@Table(name = "embeddings")
public class Embedding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "document_id", referencedColumnName = "id", nullable = false)
    private Document document;

    @JdbcTypeCode(SqlTypes.JSON) // Добавляем эту аннотацию
    @Column(columnDefinition = "JSONB", nullable = false)
    private String embedding;

    @Column(name = "embedding_norm")
    private Double embeddingNorm;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Конструкторы остаются без изменений
    public Embedding() {}

    public Embedding(Document document, JSONArray embedding, Double embeddingNorm) {
        this.document = document;
        this.embedding = embedding.toString();
        this.embeddingNorm = embeddingNorm;
    }

    // Геттеры и сеттеры остаются без изменений
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public void setEmbedding(JSONArray embedding) {
        this.embedding = embedding.toString();
    }

    public JSONArray getEmbeddingAsJson() {
        return new JSONArray(embedding);
    }

    public Double getEmbeddingNorm() { return embeddingNorm; }
    public void setEmbeddingNorm(Double embeddingNorm) { this.embeddingNorm = embeddingNorm; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}