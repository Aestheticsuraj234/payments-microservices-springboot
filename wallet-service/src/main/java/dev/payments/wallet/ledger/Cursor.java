package dev.payments.wallet.ledger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.OptionalLong;

/**
 * A page cursor: the id of the last entry on the previous page, base64-encoded
 * so it's opaque. Clients pass it back but never build one, so we can change
 * what's inside without breaking them.
 */
final class Cursor {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursor() {
    }

    static String encode(long entryId) {
        return ENCODER.encodeToString(Long.toString(entryId).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The entry id inside a cursor, or empty if the cursor is invalid. A null
     * or empty cursor means the first page: {@code Long.MAX_VALUE}.
     */
    static OptionalLong decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
        try {
            long id = Long.parseLong(new String(DECODER.decode(cursor), StandardCharsets.US_ASCII));
            // Accept only the exact cursor we would write: "+5", "05" and other
            // spellings of the same number are not cursors we ever handed out.
            if (id <= 0 || !encode(id).equals(cursor)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(id);
        } catch (IllegalArgumentException e) { // bad base64, or not a number
            return OptionalLong.empty();
        }
    }
}
