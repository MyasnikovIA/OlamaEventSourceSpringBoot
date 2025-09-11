package ru.miacomsoft.olamaeventsourcespringboot.controller;


import org.springframework.web.bind.annotation.*;
import ru.miacomsoft.olamaeventsourcespringboot.service.OllamaService;

@RestController
public class ChatQueryController {

    private final OllamaService ollamaService;

    public ChatQueryController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping(value = "/chat/{clientId}")
    public void streamEvents(@PathVariable String clientId, @RequestBody String requestBody) {
            ollamaService.sendChatQuery(clientId, requestBody);
    }
}