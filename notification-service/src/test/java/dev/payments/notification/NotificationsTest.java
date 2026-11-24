package dev.payments.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationsTest {

    private final WalletEvent.Wallet alice = new WalletEvent.Wallet(UUID.randomUUID(), UUID.randomUUID(), "USD");
    private final WalletEvent.Wallet bob = new WalletEvent.Wallet(UUID.randomUUID(), UUID.randomUUID(), "USD");

    @Test
    void aDepositNotifiesTheOwner() {
        var event = new WalletEvent(UUID.randomUUID(), WalletEvent.DEPOSIT_COMPLETED,
                new WalletEvent.Data(alice, null, null, 1050));
        assertThat(Notifications.about(event)).singleElement().satisfies(n -> {
            assertThat(n.userId()).isEqualTo(alice.ownerId());
            assertThat(n.subject()).isEqualTo("Deposit received: 10.50 USD");
        });
    }

    @Test
    void aTransferNotifiesBothSides() {
        var event = new WalletEvent(UUID.randomUUID(), WalletEvent.TRANSFER_COMPLETED,
                new WalletEvent.Data(null, alice, bob, 300));
        assertThat(Notifications.about(event))
                .extracting(Notification::userId, Notification::subject)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(bob.ownerId(), "You received 3.00 USD"),
                        org.assertj.core.groups.Tuple.tuple(alice.ownerId(), "You sent 3.00 USD"));
    }

    @Test
    void otherEventsNeedNoEmail() {
        var event = new WalletEvent(UUID.randomUUID(), "wallet.wallet.created.v1",
                new WalletEvent.Data(alice, null, null, 0));
        assertThat(Notifications.about(event)).isEmpty();
    }

    @Test
    void formatsAmountsWithTheirCurrencysDecimals() {
        assertThat(Notifications.format(1050, "USD")).isEqualTo("10.50 USD");
        assertThat(Notifications.format(5, "EUR")).isEqualTo("0.05 EUR");
        assertThat(Notifications.format(500, "JPY")).isEqualTo("500 JPY");
    }
}
