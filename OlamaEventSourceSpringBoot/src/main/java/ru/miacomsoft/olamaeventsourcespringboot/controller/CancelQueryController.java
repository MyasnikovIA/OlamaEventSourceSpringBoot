package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.miacomsoft.olamaeventsourcespringboot.service.OllamaService;

@RestController
@RequestMapping("/api/cancel")
public class CancelQueryController {

    private final OllamaService ollamaService;

    public CancelQueryController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping("/{clientId}")
    public String cancelGeneration(@PathVariable String clientId) {
        try {
            ollamaService.cancelGeneration(clientId);
            return "{\"success\": true, \"message\": \"Генерация отменена для клиента: \" + clientId}";
        } catch (Exception e) {
            return "{\"success\": false, \"error\": \"\" + e.getMessage() + \"\"}";
        }
    }
}