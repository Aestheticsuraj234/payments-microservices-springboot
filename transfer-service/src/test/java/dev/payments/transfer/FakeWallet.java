package dev.payments.transfer;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the wallet's internal API, built on the JDK's HTTP server.
 * Tests register wallets, and script how a step answers for a given wallet:
 * {@code script("hold", aliceWallet, "503", "503", "200")} makes Alice's next
 * three holds fail, fail, then succeed. Unscripted steps succeed.
 */
public final class FakeWallet {

    public record Wallet(UUID id, UUID owner, String currency) {
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<UUID, Wallet> wallets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> holdWallet = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> scripts = new ConcurrentHashMap<>();
    /** Every call, like "hold 3f2c..." or "capture 3f2c... -> 9a1b...". */
    public final List<String> calls = new CopyOnWriteArrayList<>();
    /** The traceparent header each hold, capture or release arrived with, by hold id and step. */
    public final Map<String, String> traceparents = new ConcurrentHashMap<>();
    /** How many wallet lookups reached us. */
    public final AtomicInteger lookups = new AtomicInteger();
    /** While true, every wallet lookup answers 503: an outage. */
    public volatile boolean lookupsDown;

    public FakeWallet() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public Wallet wallet(String currency) {
        var w = new Wallet(UUID.randomUUID(), UUID.randomUUID(), currency);
        wallets.put(w.id(), w);
        return w;
    }

    /** Responses like "200", "503" or "422:insufficient_funds", used in order, for step on wallet. */
    public void script(String step, UUID walletId, String... responses) {
        scripts.computeIfAbsent(step + ":" + walletId, k -> new ArrayDeque<>()).addAll(List.of(responses));
    }

    public List<String> callsFor(UUID holdId) {
        return calls.stream().filter(c -> c.contains(holdId.toString())).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String[] path = ex.getRequestURI().getPath().split("/"); // "", internal, wallets|holds, id, [action]
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path[2].equals("wallets")) {
            lookups.incrementAndGet();
            Wallet w = wallets.get(UUID.fromString(path[3]));
            if (lookupsDown) {
                respond(ex, "503:unavailable", "{}");
            } else if (w == null) {
                respond(ex, "404:not_found", "{}");
            } else {
                respond(ex, "200", """
                        {"id": "%s", "owner_id": "%s", "currency": "%s"}""".formatted(w.id(), w.owner(), w.currency()));
            }
            return;
        }
        UUID holdId = UUID.fromString(path[3]);
        String action = path.length > 4 ? path[4] : "hold";
        String traceparent = ex.getRequestHeaders().getFirst("traceparent");
        if (traceparent != null) {
            traceparents.put(action + ":" + holdId, traceparent);
        }
        UUID scriptedWallet;
        switch (action) {
            case "hold" -> {
                JsonNode req = json.readTree(body);
                scriptedWallet = UUID.fromString(req.get("wallet_id").asString());
                holdWallet.put(holdId, scriptedWallet);
                calls.add("hold " + holdId);
            }
            case "capture" -> {
                scriptedWallet = UUID.fromString(json.readTree(body).get("to_wallet_id").asString());
                calls.add("capture " + holdId + " -> " + scriptedWallet);
            }
            default -> {
                scriptedWallet = holdWallet.get(holdId);
                calls.add("release " + holdId);
            }
        }
        String response = scripts.getOrDefault(action + ":" + scriptedWallet, new ArrayDeque<>()).poll();
        respond(ex, response == null ? "200" : response, """
                {"id": "%s", "status": "%s"}""".formatted(holdId, action));
    }

    private static void respond(HttpExchange ex, String scripted, String okBody) throws IOException {
        String[] parts = scripted.split(":");
        int status = Integer.parseInt(parts[0]);
        String body = status == 200 ? okBody : """
                {"status": %d, "code": "%s"}""".formatted(status, parts.length > 1 ? parts[1] : "error");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
