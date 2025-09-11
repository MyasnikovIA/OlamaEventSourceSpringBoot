package ru.miacomsoft.oracle_bd.rag;

import org.json.JSONArray;
import ru.miacomsoft.oracle_bd.rag.utils.ConfigLoader;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class PostgresDatabase {
    private final Connection dbConnection;
    private final Properties properties;

    public PostgresDatabase(Properties properties) throws SQLException {
        this.properties = properties;

        // Используем ConfigLoader для получения URL БД
        ConfigLoader configLoader = new ConfigLoader();
        String dbUrl = configLoader.getDbUrl();

        this.dbConnection = DriverManager.getConnection(dbUrl,
                properties.getProperty("spring.datasource.username", "postgres"),
                properties.getProperty("spring.datasource.password", ""));

        createTablesIfNotExists();
        createIndexes();
        createFunctions();
    }

    public Connection getConnection() {
        return dbConnection;
    }

    private void createTablesIfNotExists() throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // Создание таблицы documents с BIGSERIAL
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS documents (
                            id BIGSERIAL PRIMARY KEY,
                            content TEXT NOT NULL,
                            metadata JSONB,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);

            // Создание таблицы эмбеддингов с BIGSERIAL
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS embeddings (
                            id BIGSERIAL PRIMARY KEY,
                            document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE,
                            embedding JSONB NOT NULL,
                            embedding_norm DOUBLE PRECISION,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE(document_id)
                        )
                    """);

            // Создание таблицы истории чатов (переименована в chat_histories)
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS chat_histories (
                            id BIGSERIAL PRIMARY KEY,
                            client_id VARCHAR(255) NOT NULL,
                            role VARCHAR(50) NOT NULL,
                            content TEXT NOT NULL,
                            metadata JSONB,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
        }
    }

    private void createIndexes() throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // Индексы для documents
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_metadata ON documents USING GIN (metadata)");

            // Индексы для embeddings
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_document_id ON embeddings(document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_embedding_norm ON embeddings(embedding_norm)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_created_at ON embeddings(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_embedding ON embeddings USING GIN (embedding)");

            // Индекс для истории чатов
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_histories_client_id ON chat_histories(client_id)");
        }
    }

    private void createFunctions() throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // Функция для вычисления косинусного сходства
            String createCosineSimilarityFunction = """
                    CREATE OR REPLACE FUNCTION cosine_similarity(vec1 JSONB, vec2 JSONB)
                    RETURNS DOUBLE PRECISION AS $$
                    DECLARE
                        dot_product DOUBLE PRECISION := 0;
                        norm1 DOUBLE PRECISION := 0;
                        norm2 DOUBLE PRECISION := 0;
                        val1 DOUBLE PRECISION;
                        val2 DOUBLE PRECISION;
                        i INTEGER;
                    BEGIN
                        FOR i IN 0..jsonb_array_length(vec1) - 1 LOOP
                            val1 := (vec1->>i)::DOUBLE PRECISION;
                            val2 := (vec2->>i)::DOUBLE PRECISION;
                            dot_product := dot_product + val1 * val2;
                            norm1 := norm1 + val1 * val1;
                            norm2 := norm2 + val2 * val2;
                        END LOOP;
                    
                        IF norm1 = 0 OR norm2 = 0 THEN
                            RETURN 0;
                        END IF;
                    
                        RETURN dot_product / (SQRT(norm1) * SQRT(norm2));
                    END;
                    $$ LANGUAGE plpgsql;
                    """;

            stmt.execute(createCosineSimilarityFunction);
            System.out.println("Функция cosine_similarity создана или обновлена");
        }
    }

    public long storeDocument(String content) throws SQLException {
        String sql = "INSERT INTO documents (content) VALUES (?) RETURNING id";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, content);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new SQLException("Failed to insert document");
        }
    }

    public void storeEmbedding(long documentId, List<Double> embedding) throws SQLException {
        double norm = calculateNorm(embedding);
        JSONArray embeddingJson = new JSONArray(embedding);

        String sql = "INSERT INTO embeddings (document_id, embedding, embedding_norm) " +
                "VALUES (?, ?::jsonb, ?) " +
                "ON CONFLICT (document_id) DO UPDATE SET " +
                "embedding = EXCLUDED.embedding, embedding_norm = EXCLUDED.embedding_norm";

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            pstmt.setString(2, embeddingJson.toString());
            pstmt.setDouble(3, norm);
            pstmt.executeUpdate();
        }
    }

    public void storeDocumentWithEmbedding(String content, List<Double> embedding) throws SQLException {
        long documentId = storeDocument(content);
        storeEmbedding(documentId, embedding);
    }

    private double calculateNorm(List<Double> embedding) {
        double sum = 0.0;
        for (double val : embedding) {
            sum += val * val;
        }
        return Math.sqrt(sum);
    }

    public List<Document> findSimilarDocuments(List<Double> queryEmbedding, int topK) throws SQLException {
        JSONArray queryEmbeddingJson = new JSONArray(queryEmbedding);

        String sql = """
                    SELECT d.id, d.content, e.embedding, 
                           cosine_similarity(e.embedding, ?::jsonb) AS cosine_similarity
                    FROM documents d
                    JOIN embeddings e ON d.id = e.document_id
                    ORDER BY cosine_similarity DESC LIMIT ?
                """;

        List<Document> results = new ArrayList<>();
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, queryEmbeddingJson.toString());
            pstmt.setInt(2, topK);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong("id");
                String content = rs.getString("content");
                String embeddingJson = rs.getString("embedding");

                JSONArray embArray = new JSONArray(embeddingJson);
                List<Double> embedding = new ArrayList<>();
                for (int i = 0; i < embArray.length(); i++) {
                    embedding.add(embArray.getDouble(i));
                }

                results.add(new Document(id, content, embedding));
            }
        }
        return results;
    }

    public void addChatMessage(String clientId, String role, String content) throws SQLException {
        String sql = "INSERT INTO chat_histories (client_id, role, content) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.setString(2, role);
            pstmt.setString(3, content);
            pstmt.executeUpdate();
        }
    }

    public List<ChatMessage> getChatHistory(String clientId) throws SQLException {
        List<ChatMessage> history = new ArrayList<>();
        String sql = "SELECT role, content FROM chat_histories WHERE client_id = ? ORDER BY created_at";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(new ChatMessage(
                        rs.getString("role"),
                        rs.getString("content")));
            }
        }
        return history;
    }

    public void clearChatHistory(String clientId) throws SQLException {
        String sql = "DELETE FROM chat_histories WHERE client_id = ?";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.executeUpdate();
        }
    }

    public void close() throws SQLException {
        if (dbConnection != null) {
            dbConnection.close();
        }
    }

    public static class Document {
        public final long id;
        public final String content;
        public final List<Double> embedding;

        public Document(long id, String content, List<Double> embedding) {
            this.id = id;
            this.content = content;
            this.embedding = embedding;
        }
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