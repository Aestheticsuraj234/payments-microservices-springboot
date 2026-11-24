package dev.payments.wallet.events;

import dev.payments.wallet.ledger.Account;
import java.time.Instant;
import java.util.UUID;

/**
 * The wallet's event contract: what other services may rely on. Every event is
 * an {@link Envelope} on the {@code wallet.events} topic, keyed by the wallet
 * it is about, so all events of one wallet stay in order.
 *
 * <p>An event type names the service, what happened, and the version of its
 * data. A breaking change is a new version, published next to the old one,
 * never a silent change to an existing one.
 */
public final class WalletEvents {

    public static final String WALLET_CREATED = "wallet.wallet.created.v1";
    public static final String DEPOSIT_COMPLETED = "wallet.deposit.completed.v1";
    public static final String TRANSFER_COMPLETED = "wallet.transfer.completed.v1";

    private WalletEvents() {
    }

    /**
     * What every event looks like on the wire.
     *
     * @param id          unique per event; the same on every redelivery
     * @param type        one of the constants above
     * @param occurredAt  when the change committed (near enough: when it was written)
     * @param aggregateId the wallet the event is about; also the message key
     * @param data        the type-specific part
     */
    public record Envelope(UUID id, String type, Instant occurredAt, UUID aggregateId, Object data) {
    }

    /** A wallet, as events describe it. */
    public record Wallet(UUID id, UUID ownerId, String currency) {
        public static Wallet of(Account a) {
            return new Wallet(a.id(), a.ownerId(), a.currency());
        }
    }

    public record WalletCreated(Wallet wallet) {
    }

    /** Money came into a wallet from outside. Amounts are minor units of the wallet's currency. */
    public record DepositCompleted(UUID transactionId, Wallet wallet, long amount) {
    }

    /** Money moved from one wallet to another. */
    public record TransferCompleted(UUID transactionId, Wallet from, Wallet to, long amount) {
    }
}
