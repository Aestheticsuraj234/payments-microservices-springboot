package dev.payments.notification;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

/**
 * Decides who to tell what about an event. Pure logic, no I/O: easy to test,
 * and the one place to look when the wording changes.
 */
public final class Notifications {

    private Notifications() {
    }

    public static List<Notification> about(WalletEvent event) {
        var d = event.data();
        return switch (event.type()) {
            case WalletEvent.DEPOSIT_COMPLETED -> List.of(new Notification(event.id(), d.wallet().ownerId(),
                    "Deposit received: " + format(d.amount(), d.wallet().currency()),
                    "We added " + format(d.amount(), d.wallet().currency()) + " to your wallet."));
            case WalletEvent.TRANSFER_COMPLETED -> List.of(
                    new Notification(event.id(), d.to().ownerId(),
                            "You received " + format(d.amount(), d.to().currency()),
                            "Someone sent " + format(d.amount(), d.to().currency()) + " to your wallet."),
                    new Notification(event.id(), d.from().ownerId(),
                            "You sent " + format(d.amount(), d.from().currency()),
                            "Your transfer of " + format(d.amount(), d.from().currency()) + " has arrived."));
            // Other events (wallet.created, and any new type) need no email.
            default -> List.of();
        };
    }

    /**
     * Minor units back to a human amount: 1050 USD is "10.50 USD", 500 JPY is
     * "500 JPY". The number of decimals is the currency's, not always two.
     */
    public static String format(long minorUnits, String currency) {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return BigDecimal.valueOf(minorUnits, digits).toPlainString() + " " + currency;
    }
}
