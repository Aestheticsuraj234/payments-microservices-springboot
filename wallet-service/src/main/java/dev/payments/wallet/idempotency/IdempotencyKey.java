package dev.payments.wallet.idempotency;

import dev.payments.wallet.error.IdempotencyException;
import dev.payments.wallet.error.IdempotencyException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Names one operation: the key a client sent, the caller who sent it, and a
 * fingerprint of the request it came with.
 *
 * @param scope       who sent the key. Clients choose keys, so a key is only
 *                    unique per caller: two clients may both send "1".
 * @param value       the {@code Idempotency-Key} header
 * @param fingerprint SHA-256 of the method, the path and the canonical body
 */
public record IdempotencyKey(String scope, String value, byte[] fingerprint) {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * The key for a request. {@code body} is the decoded request body: it is
     * encoded again for the fingerprint, so spacing and field order in what
     * the client sent don't change it, but any value does.
     */
    public static IdempotencyKey of(String header, String scope, String method, String path, Object body,
            JsonMapper json) {
        if (header == null || header.isEmpty() || header.length() > 255) {
            throw new IdempotencyException(Reason.KEY_REQUIRED);
        }
        return new IdempotencyKey(scope, header, fingerprint(method, path, json.writeValueAsBytes(body)));
    }

    static byte[] fingerprint(String method, String path, byte[] canonicalBody) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            return sha256.digest(canonicalBody);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
