package ru.miacomsoft.olamaeventsourcespringboot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.miacomsoft.olamaeventsourcespringboot.model.Document;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    @Query(value = "SELECT COUNT(*) FROM documents", nativeQuery = true)
    Long countDocuments();

    @Query(value = "SELECT * FROM documents WHERE content LIKE %:query%", nativeQuery = true)
    List<Document> searchByContent(@Param("query") String query);
}