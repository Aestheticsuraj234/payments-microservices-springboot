package dev.payments.wallet.holds;

import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.LedgerRepository;
import dev.payments.wallet.money.Money;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wallet's internal API, for other services only. The gateway has no
 * route to {@code /internal/**}, so no client can reach it: the transfer
 * service calls it directly, inside our network.
 *
 * <p>Holds are addressed by an id the caller chooses, and placed with
 * {@code PUT}: "make this hold exist", safe to repeat.
 */
@RestController
@RequestMapping("/internal")
class InternalController {

    record WalletInfo(UUID id, UUID ownerId, String currency) {
    }

    record PlaceHoldRequest(@Nullable UUID walletId, @Nullable UUID ownerId, long amount, @Nullable String currency) {
    }

    record CaptureRequest(@Nullable UUID toWalletId) {
    }

    record HoldResponse(UUID id, UUID walletId, Money amount, String status, @Nullable UUID settledTo) {
        static HoldResponse of(Hold h) {
            return new HoldResponse(h.id(), h.walletId(), new Money(h.amount(), h.currency()),
                    h.status().dbValue(), h.settledTo());
        }
    }

    private final HoldService holds;
    private final LedgerRepository ledger;

    InternalController(HoldService holds, LedgerRepository ledger) {
        this.holds = holds;
        this.ledger = ledger;
    }

    /** Who owns a wallet, and in which currency: what the transfer service checks before it accepts a transfer. */
    @GetMapping("/wallets/{id}")
    WalletInfo wallet(@PathVariable UUID id) {
        Account a = ledger.findAccount(id)
                .filter(Account::isWallet)
                .orElseThrow(() -> new NotFoundException("wallet not found"));
        return new WalletInfo(a.id(), a.ownerId(), a.currency());
    }

    @PutMapping("/holds/{id}")
    HoldResponse place(@PathVariable UUID id, @RequestBody PlaceHoldRequest body) {
        if (body.walletId() == null || body.ownerId() == null) {
            throw new NotFoundException("wallet not found");
        }
        return HoldResponse.of(holds.place(id, body.ownerId(), body.walletId(), new Money(body.amount(), body.currency())));
    }

    @PostMapping("/holds/{id}/capture")
    HoldResponse capture(@PathVariable UUID id, @RequestBody CaptureRequest body) {
        if (body.toWalletId() == null) {
            throw new NotFoundException("wallet not found");
        }
        return HoldResponse.of(holds.capture(id, body.toWalletId()));
    }

    @PostMapping("/holds/{id}/release")
    HoldResponse release(@PathVariable UUID id) {
        return HoldResponse.of(holds.release(id));
    }
}
