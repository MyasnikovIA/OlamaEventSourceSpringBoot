package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;
import ru.miacomsoft.olamaeventsourcespringboot.service.OllamaService;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final OllamaService ollamaService;

    public SetupController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping("/save")
    public String saveSettings(@RequestBody String requestBody) {
        try {
            JSONObject settings = new JSONObject(requestBody);

            // Сохраняем настройки в сервисе
            if (settings.has("chatModel")) {
                // Здесь можно обновить модель, если нужно
                ollamaService.setModelName(settings.getString("chatModel"));
            }
            if (settings.has("embeddingModel")) {
                // Здесь можно обновить модель, если нужно
                ollamaService.setEmbeddingName(settings.getString("embeddingModel"));
            }


            if (settings.has("systemPrompt")) {
                ollamaService.setPROMPT_CHAR(settings.getString("systemPrompt"));
            }

            if (settings.has("generatePrompt")) {
                ollamaService.setPROMPT_GENERATE(settings.getString("generatePrompt"));
            }

            // Создаем ответ об успешном сохранении
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Настройки успешно сохранены");
            response.put("savedSettings", settings);

            return response.toString();

        } catch (Exception e) {
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("success", false);
            errorResponse.put("error", "Ошибка при сохранении настроек: " + e.getMessage());
            return errorResponse.toString();
        }
    }

    @GetMapping("/load")
    public String loadSettings() {
        try {
            JSONObject settings = new JSONObject();

            // Загружаем текущие настройки из сервиса
            settings.put("chatModel", ollamaService.getModelName());
            settings.put("embeddingModel", ollamaService.getEmbeddingName());
            settings.put("systemPrompt", ollamaService.getPROMPT_CHAR());
            settings.put("generatePrompt", ollamaService.getPROMPT_GENERATE());
            // embeddingModel может храниться в другом сервисе или базе данных

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("settings", settings);

            return response.toString();

        } catch (Exception e) {
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("success", false);
            errorResponse.put("error", "Ошибка при загрузке настроек: " + e.getMessage());
            return errorResponse.toString();
        }
    }
}