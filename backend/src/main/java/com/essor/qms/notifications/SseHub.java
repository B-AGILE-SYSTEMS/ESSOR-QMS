package com.essor.qms.notifications;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Flux Server-Sent Events par utilisateur (identifiant technique, unique tous tenants confondus). */
@Component
class SseHub {

    static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_PER_USER = 5;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        while (list.size() >= MAX_PER_USER) {
            SseEmitter oldest = list.remove(0);
            oldest.complete();
        }
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    void send(UUID userId, Object notification) {
        for (SseEmitter e : emitters.getOrDefault(userId, List.of())) {
            try {
                e.send(SseEmitter.event().name("notification").data(notification, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                emitters.getOrDefault(userId, List.of()).remove(e);
            }
        }
    }

    /** Commentaire périodique : garde la connexion ouverte derrière les proxys. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emitters.forEach((user, list) -> list.forEach(e -> {
            try {
                e.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                list.remove(e);
            }
        }));
    }
}
