package dev.payments.wallet.idempotency;

/**
 * What we answered for a key: replayed byte for byte to every retry.
 *
 * @param replayed true when this is a stored response sent again
 */
public record StoredResponse(int status, byte[] body, boolean replayed) {
}
