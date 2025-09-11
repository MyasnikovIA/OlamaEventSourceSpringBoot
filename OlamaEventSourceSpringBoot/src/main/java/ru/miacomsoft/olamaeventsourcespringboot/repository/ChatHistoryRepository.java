package ru.miacomsoft.olamaeventsourcespringboot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.miacomsoft.olamaeventsourcespringboot.model.ChatHistory;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    List<ChatHistory> findByClientIdOrderByCreatedAtAsc(String clientId);

    @Query("SELECT COUNT(ch) FROM ChatHistory ch WHERE ch.clientId = :clientId")
    Long countByClientId(@Param("clientId") String clientId);

    void deleteByClientId(String clientId);

    @Query("SELECT ch FROM ChatHistory ch WHERE ch.clientId = :clientId ORDER BY ch.createdAt DESC LIMIT :limit")
    List<ChatHistory> findRecentByClientId(@Param("clientId") String clientId, @Param("limit") int limit);
}