package dev.payments.wallet.money;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.FieldErrors;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The rules every amount of money must follow before it can move. They live in
 * one place, so deposits and transfers can't drift apart.
 */
@Component
public class MoneyRules {

    /** The shape of an ISO 4217 currency code, like USD or EUR. */
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    private final long maxAmount;

    public MoneyRules(WalletProperties properties) {
        this.maxAmount = properties.maxAmount();
    }

    /** Records a problem in {@code errors} if {@code amount} can't be moved. */
    public void checkAmount(FieldErrors errors, String field, long amount) {
        if (amount <= 0) {
            errors.add(field, "must be a positive number of minor units, like 1050 for 10.50");
        } else if (amount > maxAmount) {
            errors.add(field, "must be at most " + maxAmount);
        }
    }

    /** Records a problem in {@code errors} if {@code currency} isn't shaped like a currency code. */
    public void checkCurrency(FieldErrors errors, String field, String currency) {
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches()) {
            errors.add(field, "must be a three-letter ISO 4217 code, like USD");
        }
    }
}
