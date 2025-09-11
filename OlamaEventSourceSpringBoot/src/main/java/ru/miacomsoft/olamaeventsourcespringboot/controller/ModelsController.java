package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.json.JSONArray;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.miacomsoft.olamaeventsourcespringboot.service.OllamaService;

@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final OllamaService ollamaService;

    public ModelsController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @GetMapping
    public String getAvailableModels() {
        JSONArray models = ollamaService.getAvailableModelsWithDetails();
        org.json.JSONObject response = new org.json.JSONObject();
        response.put("success", true);
        response.put("models", models);
        response.put("count", models.length());
        response.put("host", ollamaService.getOllamaHost());
        return response.toString();
    }
}