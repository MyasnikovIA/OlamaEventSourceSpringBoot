package ru.miacomsoft.olamaeventsourcespringboot.controller;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.miacomsoft.olamaeventsourcespringboot.service.SseService;

@RestController
public class SseController {

    private final SseService sseService;
    public SseController(SseService sseService) {
        this.sseService = sseService;
    }

    // Простой пример SSE
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@RequestParam String chat_id) {
        return sseService.createEmitter(chat_id);
    }
}