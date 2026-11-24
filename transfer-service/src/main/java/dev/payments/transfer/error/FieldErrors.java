package dev.payments.transfer.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects problems with a request's fields, so a client learns about all of
 * them in one response instead of fixing them one at a time.
 *
 * <pre>{@code
 * var errors = new FieldErrors();
 * if (amount <= 0) errors.add("amount", "must be positive");
 * errors.throwIfAny();
 * }</pre>
 */
public final class FieldErrors {

    private final Map<String, String> fields = new LinkedHashMap<>();

    /** Records a problem with one field. The first problem recorded for a field wins. */
    public FieldErrors add(String field, String problem) {
        fields.putIfAbsent(field, problem);
        return this;
    }

    public boolean has(String field) {
        return fields.containsKey(field);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** Throws a {@link ValidationException} if any field has a problem. */
    public void throwIfAny() {
        if (!fields.isEmpty()) {
            throw new ValidationException(fields);
        }
    }
}
