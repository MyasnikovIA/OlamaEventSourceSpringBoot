package ru.miacomsoft.olamaeventsourcespringboot.service;

import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SseService {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SseEmitter createEmitter(String clientId) {
        SseEmitter emitter = new SseEmitter(6000_000L);
        emitter.onCompletion(() -> emitters.remove(clientId));
        emitter.onTimeout(() -> emitters.remove(clientId));
        emitter.onError((e) -> emitters.remove(clientId));
        emitters.put(clientId, emitter);
        return emitter;
    }

    public void sendEventToClient(String clientId, String eventName, JSONObject data) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            executor.execute(() -> {
                try {
                    SseEmitter.SseEventBuilder event = SseEmitter.event().data(data.toString()).name(eventName).id(String.valueOf(System.currentTimeMillis()));
                    emitter.send(event);
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    emitters.remove(clientId);
                }
            });
        }
    }


    public void broadcastEvent(String eventName, JSONObject data) {
        emitters.forEach((clientId, emitter) -> {
            executor.execute(() -> {
                try {
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .data(data)
                            .name(eventName);
                    emitter.send(event);
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    emitters.remove(clientId);
                }
            });
        });
    }

    public void removeEmitter(String clientId) {
        SseEmitter emitter = emitters.remove(clientId);
        if (emitter != null) {
            emitter.complete();
        }
    }

}