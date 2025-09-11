package ru.miacomsoft.olamaeventsourcespringboot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.miacomsoft.olamaeventsourcespringboot.model.Embedding;

import java.util.List;

@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {
    @Query(value = "SELECT COUNT(*) FROM embeddings", nativeQuery = true)
    Long countEmbeddings();

    @Query(value = "SELECT AVG(embedding_norm) FROM embeddings", nativeQuery = true)
    Double averageEmbeddingNorm();

    boolean existsByDocumentId(Long documentId);

    @Query(value = "SELECT e.* FROM embeddings e " +
            "ORDER BY embedding_norm DESC LIMIT :limit", nativeQuery = true)
    List<Embedding> findTopByNorm(@Param("limit") int limit);

    @Query(value = """
    SELECT d.id, d.content, d.metadata, 
           cosine_similarity(e.embedding, CAST(:queryEmbeddingStr AS jsonb)) as similarity
    FROM embeddings e
    JOIN documents d ON e.document_id = d.id
    WHERE cosine_similarity(e.embedding, CAST(:queryEmbeddingStr AS jsonb)) >= :threshold
    ORDER BY similarity DESC
    LIMIT :topK
""", nativeQuery = true)
    List<Object[]> findSimilarDocuments(
            @Param("queryEmbeddingStr") String queryEmbeddingStr,
            @Param("topK") int topK,
            @Param("threshold") double threshold);
}