package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;
import ru.miacomsoft.olamaeventsourcespringboot.service.ChatHistoryService;

@RestController
@RequestMapping("/api/history")
public class HistoriesController {

    private final ChatHistoryService chatHistoryService;

    public HistoriesController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/{clientId}")
    public String getChatHistory(@PathVariable String clientId,
                                 @RequestParam(required = false) Integer limit) {
        JSONArray history;
        if (limit != null) {
            history = chatHistoryService.getRecentChatHistory(clientId, limit);
        } else {
            history = chatHistoryService.getChatHistory(clientId);
        }

        JSONObject response = new JSONObject();
        response.put("clientId", clientId);
        response.put("history", history);
        response.put("messageCount", history.length());
        response.put("totalCount", chatHistoryService.getMessageCount(clientId));
        return response.toString();
    }

    @DeleteMapping("/{clientId}")
    public String clearChatHistory(@PathVariable String clientId) {
        chatHistoryService.clearChatHistory(clientId);
        JSONObject response = new JSONObject();
        response.put("clientId", clientId);
        response.put("message", "Chat history cleared successfully");
        response.put("status", "success");
        return response.toString();
    }

    @GetMapping("/{clientId}/count")
    public String getMessageCount(@PathVariable String clientId) {
        Long count = chatHistoryService.getMessageCount(clientId);
        JSONObject response = new JSONObject();
        response.put("clientId", clientId);
        response.put("messageCount", count);
        return response.toString();
    }
}