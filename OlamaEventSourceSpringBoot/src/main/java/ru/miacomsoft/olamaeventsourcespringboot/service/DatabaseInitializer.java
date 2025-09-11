package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.Properties;

@Service
public class DatabaseInitializer {
    public interface Callback {
        public void call();
    }

    public static void initializeDatabase(Callback callback) {
        DatabaseInitializer databaseInitializer = new DatabaseInitializer();
        databaseInitializer.initializeDatabaseLocal(callback);
    }

    private void initializeDatabaseLocal(Callback callback) {
        Properties props = loadPropertiesFromClasspath("application.properties");

        String username = props.getProperty("spring.datasource.username");
        String password = props.getProperty("spring.datasource.password");
        Properties dbParams = new Properties();
        dbParams.setProperty("user", username);
        dbParams.setProperty("password", password);

        ensureDatabaseExists(props, dbParams);
        ensureTablesExist(props, dbParams);
        ensureFunctionsExist(props, dbParams); // Добавляем создание функций
        ensureIndexesExist(props, dbParams);
        callback.call();
    }

    public static Properties loadPropertiesFromClasspath(String fileName) {
        Properties props = new Properties();
        try (InputStream input = DatabaseInitializer.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input == null) {
                throw new IOException("File not found in classpath: " + fileName);
            }
            props.load(input);
            System.out.println("Successfully loaded properties from classpath: " + fileName);
        } catch (IOException ex) {
            System.err.println("Error loading properties from classpath: " + ex.getMessage());
        }
        return props;
    }

    public void ensureDatabaseExists(Properties props, Properties dbParams) {
        String embeddingServerPort = props.getProperty("spring.datasource.port");
        String embeddingServerHost = props.getProperty("spring.datasource.host");
        String workDatabase = props.getProperty("spring.datasource.url");
        String dbName = workDatabase.substring(workDatabase.lastIndexOf("/") + 1);
        try (Connection tempConn = DriverManager.getConnection("jdbc:postgresql://" + embeddingServerHost + ":" + embeddingServerPort + "/postgres", dbParams);
             Statement stmt = tempConn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");

            if (!rs.next()) {
                stmt.executeUpdate("CREATE DATABASE " + dbName);
                System.out.println("База данных " + dbName + " успешно создана");
            } else {
                System.out.println("База данных " + dbName + " уже существует");
            }

        } catch (SQLException e) {
            System.err.println("Ошибка при создании базы данных: " + e.getMessage());
        }
    }

    private void ensureTablesExist(Properties props, Properties dbParams) {
        String embeddingServerPort = props.getProperty("spring.datasource.port");
        String embeddingServerHost = props.getProperty("spring.datasource.host");
        String workDatabase = props.getProperty("spring.datasource.url");
        String dbName = workDatabase.substring(workDatabase.lastIndexOf("/") + 1);
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + embeddingServerHost + ":" + embeddingServerPort + "/" + dbName, dbParams);
             Statement stmt = conn.createStatement()) {

            // Проверяем существование таблиц и их структуру
            if (!tableExists(conn, "documents") || !columnHasCorrectType(conn, "documents", "id", "bigint")) {
                // Удаляем старую таблицу если она существует с неправильным типом
                stmt.execute("DROP TABLE IF EXISTS documents CASCADE");

                // Create documents table with BIGSERIAL for compatibility with Long
                String createDocumentsTable = """
                            CREATE TABLE IF NOT EXISTS documents (
                                id BIGSERIAL PRIMARY KEY,
                                content TEXT NOT NULL,
                                metadata JSONB,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )
                        """;
                stmt.execute(createDocumentsTable);
                System.out.println("Таблица documents создана с правильным типом BIGSERIAL");
            }

            if (!tableExists(conn, "embeddings") || !columnHasCorrectType(conn, "embeddings", "id", "bigint")) {
                // Удаляем старую таблицу если она существует с неправильным типом
                stmt.execute("DROP TABLE IF EXISTS embeddings CASCADE");

                // Create embeddings table with BIGSERIAL for compatibility with Long
                String createEmbeddingsTable = """
                            CREATE TABLE IF NOT EXISTS embeddings (
                                id BIGSERIAL PRIMARY KEY,
                                document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE,
                                embedding JSONB NOT NULL,
                                embedding_norm DOUBLE PRECISION,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                UNIQUE(document_id)
                            )
                        """;
                stmt.execute(createEmbeddingsTable);
                System.out.println("Таблица embeddings создана с правильным типом BIGSERIAL");
            }
            // Добавляем таблицу chat_histories
            if (!tableExists(conn, "chat_histories") || !columnHasCorrectType(conn, "chat_histories", "id", "bigint")) {
                stmt.execute("DROP TABLE IF EXISTS chat_histories CASCADE");

                String createChatHistoriesTable = """
                            CREATE TABLE IF NOT EXISTS chat_histories (
                                id BIGSERIAL PRIMARY KEY,
                                client_id VARCHAR(255) NOT NULL,
                                role VARCHAR(50) NOT NULL,
                                content TEXT NOT NULL,
                                metadata JSONB,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )
                        """;
                stmt.execute(createChatHistoriesTable);
                System.out.println("Таблица chat_histories создана с правильным типом BIGSERIAL");
            }
            System.out.println("Таблицы успешно созданы или уже существуют с правильными типами");

        } catch (SQLException e) {
            System.err.println("Ошибка при создании таблиц: " + e.getMessage());
        }
    }

    private void ensureFunctionsExist(Properties props, Properties dbParams) {
        String embeddingServerPort = props.getProperty("spring.datasource.port");
        String embeddingServerHost = props.getProperty("spring.datasource.host");
        String workDatabase = props.getProperty("spring.datasource.url");
        String dbName = workDatabase.substring(workDatabase.lastIndexOf("/") + 1);

        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + embeddingServerHost + ":" + embeddingServerPort + "/" + dbName, dbParams);
             Statement stmt = conn.createStatement()) {

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

        } catch (SQLException e) {
            System.err.println("Ошибка при создании функций: " + e.getMessage());
        }
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '" + tableName + "')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private boolean columnHasCorrectType(Connection conn, String tableName, String columnName, String expectedType) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '" + tableName + "' AND column_name = '" + columnName + "'")) {
            if (rs.next()) {
                String actualType = rs.getString(1);
                return actualType.equals(expectedType);
            }
            return false;
        }
    }

    private void ensureIndexesExist(Properties props, Properties dbParams) {
        String embeddingServerPort = props.getProperty("spring.datasource.port");
        String embeddingServerHost = props.getProperty("spring.datasource.host");
        String workDatabase = props.getProperty("spring.datasource.url");
        String dbName = workDatabase.substring(workDatabase.lastIndexOf("/") + 1);

        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + embeddingServerHost + ":" + embeddingServerPort + "/" + dbName, dbParams);
             Statement stmt = conn.createStatement()) {

            // Indexes for documents table
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_metadata ON documents USING GIN (metadata)");

            // Indexes for embeddings table
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_document_id ON embeddings(document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_embedding_norm ON embeddings(embedding_norm)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_created_at ON embeddings(created_at)");

            // GIN index for JSONB embedding field for faster JSON queries
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_embedding ON embeddings USING GIN (embedding)");

            System.out.println("Индексы успешно созданы или уже существуют");

        } catch (SQLException e) {
            System.err.println("Ошибка при создании индексов: " + e.getMessage());
        }
    }
}