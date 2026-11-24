package dev.payments.notification;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The parts of the wallet's events this service reads, and nothing more: a
 * "tolerant reader". Fields we don't know are ignored, so the wallet can add
 * fields without breaking us. We don't share a library with the wallet: the
 * contract is the JSON, documented in docs/architecture.md.
 */
public record WalletEvent(UUID id, String type, Data data) {

    public static final String DEPOSIT_COMPLETED = "wallet.deposit.completed.v1";
    public static final String TRANSFER_COMPLETED = "wallet.transfer.completed.v1";

    public record Wallet(UUID id, UUID ownerId, String currency) {
    }

    public record Data(@Nullable Wallet wallet, @Nullable Wallet from, @Nullable Wallet to, long amount) {
    }
}
