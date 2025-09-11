package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.miacomsoft.olamaeventsourcespringboot.model.ChatHistory;
import ru.miacomsoft.olamaeventsourcespringboot.repository.ChatHistoryRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatHistoryService {

    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @Transactional
    public void saveMessage(String clientId, String role, String content, JSONObject metadata) {
        ChatHistory chatHistory = new ChatHistory(clientId, role, content, metadata);
        chatHistoryRepository.save(chatHistory);
    }

    @Transactional
    public void saveMessage(String clientId, JSONObject message) {
        String role = message.getString("role");
        String content = message.getString("content");
        JSONObject metadata = message.optJSONObject("metadata");
        saveMessage(clientId, role, content, metadata);
    }

    public JSONArray getChatHistory(String clientId) {
        List<ChatHistory> histories = chatHistoryRepository.findByClientIdOrderByCreatedAtAsc(clientId);
        JSONArray historyArray = new JSONArray();

        for (ChatHistory history : histories) {
            historyArray.put(history.toJson());
        }

        return historyArray;
    }

    public JSONArray getRecentChatHistory(String clientId, int limit) {
        List<ChatHistory> histories = chatHistoryRepository.findRecentByClientId(clientId, limit);
        JSONArray historyArray = new JSONArray();

        for (ChatHistory history : histories) {
            historyArray.put(history.toJson());
        }

        return historyArray;
    }

    @Transactional
    public void clearChatHistory(String clientId) {
        chatHistoryRepository.deleteByClientId(clientId);
    }

    public Long getMessageCount(String clientId) {
        return chatHistoryRepository.countByClientId(clientId);
    }

    @Transactional
    public void saveMessages(String clientId, List<JSONObject> messages) {
        for (JSONObject message : messages) {
            saveMessage(clientId, message);
        }
    }

    public List<JSONObject> getChatHistoryAsList(String clientId) {
        List<ChatHistory> histories = chatHistoryRepository.findByClientIdOrderByCreatedAtAsc(clientId);
        return histories.stream()
                .map(ChatHistory::toJson)
                .collect(Collectors.toList());
    }
}