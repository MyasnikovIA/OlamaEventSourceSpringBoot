package ru.miacomsoft.olamaeventsourcespringboot.controller;

import org.springframework.web.bind.annotation.*;
import ru.miacomsoft.olamaeventsourcespringboot.service.OllamaService;

@RestController
public class GenerateQueryController {

    private final OllamaService ollamaService;

    public GenerateQueryController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping(value = "/generate/{clientId}")
    public void streamGenerateEvents(@PathVariable String clientId, @RequestBody String requestBody) {
        ollamaService.sendGenerateQuery(clientId, requestBody);
    }
}