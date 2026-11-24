package dev.payments.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stands in for the services behind the gateway, using the JDK's built-in HTTP
 * server. It serves a JWKS like the auth service does, and answers every other
 * request by echoing what the gateway sent it, so tests can see the headers
 * that arrived downstream.
 */
final class FakeServices {

    final RSAKey key;
    private final HttpServer server;

    FakeServices() {
        try {
            key = new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        server.createContext("/.well-known/jwks.json", ex -> respond(ex, jwks));
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().contains("/slow")) {
                sleep(1_500); // slower than Spring Cloud's default 1 s time limiter
            }
            respond(ex, """
                {"path": "%s", "user": %s, "request_id": "%s"}"""
                .formatted(ex.getRequestURI().getPath(),
                        quoteOrNull(ex.getRequestHeaders().getFirst("X-User-ID")),
                        ex.getRequestHeaders().getFirst("X-Request-ID")));
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    /** A valid access token for {@code user}, signed like the auth service signs them. */
    String token(UUID user) {
        return token(key, user, "payments", Instant.now());
    }

    static String token(RSAKey key, UUID user, String audience, Instant issuedAt) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer("payments-auth")
                    .subject(user.toString())
                    .audience(audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(Duration.ofMinutes(15))))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
