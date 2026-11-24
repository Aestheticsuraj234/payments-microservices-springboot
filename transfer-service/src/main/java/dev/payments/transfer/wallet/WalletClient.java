package dev.payments.transfer.wallet;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * The wallet's internal API, as a Java interface. Spring generates the
 * implementation: each method becomes an HTTP request, and each response is
 * converted back to a record. No HTTP code to write, and the contract is in
 * one place.
 *
 * <p>Base URL and timeouts come from the {@code spring.http.serviceclient.wallet.*}
 * properties; see {@link WalletClientConfig}.
 */
@HttpExchange("/internal")
public interface WalletClient {

    record WalletInfo(UUID id, UUID ownerId, String currency) {
    }

    record Amount(long amount, String currency) {
    }

    record HoldInfo(UUID id, UUID walletId, Amount amount, String status) {
    }

    record PlaceHold(UUID walletId, UUID ownerId, long amount, String currency) {
    }

    record Capture(UUID toWalletId) {
    }

    @GetExchange("/wallets/{id}")
    WalletInfo wallet(@PathVariable UUID id);

    @PutExchange("/holds/{id}")
    HoldInfo placeHold(@PathVariable UUID id, @RequestBody PlaceHold body);

    @PostExchange("/holds/{id}/capture")
    HoldInfo capture(@PathVariable UUID id, @RequestBody Capture body);

    @PostExchange("/holds/{id}/release")
    HoldInfo release(@PathVariable UUID id);
}
