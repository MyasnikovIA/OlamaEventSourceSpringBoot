package ru.miacomsoft.oracle_bd.rag.utils;

import ru.miacomsoft.olamaeventsourcespringboot.service.DatabaseInitializer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {
    private Properties properties;

    public ConfigLoader() {
        this("application.properties");
    }

    public ConfigLoader(String configFile) {
        properties = new Properties();
        try {
            properties.load(new FileInputStream(configFile));
        } catch (IOException e) {
            properties = loadPropertiesFromClasspath(configFile);
            if (properties==null) {
                setDefaultProperties();
            }
        }
    }

    private void setDefaultProperties() {

        properties.setProperty("rag.generation.model", "deepseek-coder-v2:16b");
        properties.setProperty("rag.chat.model", "deepseek-coder-v2:16b");
        properties.setProperty("rag.similarity.threshold", "0.9");
        properties.setProperty("rag.ollama.server.host", "localhost");
        properties.setProperty("rag.ollama.server.port", "11434");
        properties.setProperty("rag.embedding.model", "all-minilm:22m");
        properties.setProperty("rag.embedding.host", "localhost");
        properties.setProperty("rag.embedding.server.port", "11434");

        properties.setProperty("spring.datasource.username", "postgres");
        properties.setProperty("spring.datasource.password", "XXXXXXXXXX");
        properties.setProperty("spring.datasource.database", "rag_database_3");
        properties.setProperty("spring.datasource.host", "localhost");
        properties.setProperty("spring.datasource.port", "5432");
        // spring.datasource.url=jdbc:postgresql://192.168.15.6:5432/rag_database_3
    }

    public Properties getProperties() {
        return properties;
    }

    public String getDbUrl() {
        return String.format("jdbc:postgresql://%s:%s/%s",
                properties.getProperty("spring.datasource.host"),
                properties.getProperty("spring.datasource.port"),
                properties.getProperty("spring.datasource.database"));
    }

    public String getOllamaUrl() {
        return String.format("http://%s:%s",properties.getProperty("rag.ollama.server.host"),properties.getProperty("rag.ollama.server.port"));
    }

    public String getEmbeddingServiceUrl() {
        return String.format("http://%s:%s/embed",properties.getProperty("rag.embedding.host"),properties.getProperty("rag.embedding.port"));
    }

    public  Properties loadPropertiesFromClasspath(String fileName) {
        Properties props = new Properties();
        try (InputStream input = DatabaseInitializer.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input == null) {
                throw new IOException("File not found in classpath: " + fileName);
            }
            props.load(input);
        } catch (IOException ex) {
            System.err.println("Error loading properties from classpath: " + ex.getMessage());
            return null;
        }
        return props;
    }

}