package dev.payments.notification;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The auth service's {@code /internal/users/{id}}, scripted: users, unknown users, and outages. */
final class FakeAuth {

    private final HttpServer server;
    private final Map<UUID, String> emails = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Integer>> outages = new ConcurrentHashMap<>();

    FakeAuth() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/users/", ex -> {
            UUID id = UUID.fromString(ex.getRequestURI().getPath().substring("/internal/users/".length()));
            Integer outage = outages.getOrDefault(id, new ArrayDeque<>()).poll();
            String email = emails.get(id);
            int status = outage != null ? outage : email == null ? 404 : 200;
            byte[] body = (status == 200 ? "{\"id\": \"" + id + "\", \"email\": \"" + email + "\"}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    /** A user with a fresh email address, so tests can find their own emails. */
    UUID user() {
        UUID id = UUID.randomUUID();
        emails.put(id, "user-" + id + "@example.com");
        return id;
    }

    String email(UUID id) {
        return emails.get(id);
    }

    /** The next lookups of this user answer with these statuses, like 503. */
    void failNext(UUID id, Integer... statuses) {
        outages.computeIfAbsent(id, k -> new ArrayDeque<>()).addAll(List.of(statuses));
    }
}
