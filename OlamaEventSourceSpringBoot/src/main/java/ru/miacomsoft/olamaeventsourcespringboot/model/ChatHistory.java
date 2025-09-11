package ru.miacomsoft.olamaeventsourcespringboot.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.json.JSONObject;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_histories")
public class ChatHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ChatHistory() {}

    public ChatHistory(String clientId, String role, String content, JSONObject metadata) {
        this.clientId = clientId;
        this.role = role;
        this.content = content;
        this.metadata = metadata != null ? metadata.toString() : null;
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public void setMetadata(JSONObject metadata) {
        this.metadata = metadata != null ? metadata.toString() : null;
    }

    public JSONObject getMetadataAsJson() {
        return metadata != null ? new JSONObject(metadata) : new JSONObject();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("role", role);
        json.put("content", content);
        if (metadata != null) {
            json.put("metadata", new JSONObject(metadata));
        }
        return json;
    }
}