package dev.payments.wallet.error;

import java.util.Map;
import java.util.TreeMap;

/** Some fields of a request are invalid. {@link #fields()} says which, and why. */
public final class ValidationException extends PaymentsException {

    private final Map<String, String> fields;

    public ValidationException(Map<String, String> fields) {
        super("invalid input: " + String.join(", ", new TreeMap<>(fields).keySet()));
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
