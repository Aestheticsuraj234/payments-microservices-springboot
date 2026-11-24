package dev.payments.transfer.saga;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.payments.transfer.web.Caller;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    record TransferRequest(@Nullable String from, @Nullable String to, long amount, @Nullable String currency) {
    }

    record Amount(long amount, String currency) {
    }

    record StepResponse(String step, String outcome, String detail, Instant at) {
    }

    record TransferResponse(UUID id, UUID from, UUID to, Amount amount, String state, @Nullable String failureReason,
            Instant createdAt, Instant updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<StepResponse> steps) {
        static TransferResponse of(Transfer t, @Nullable List<TransferRepository.StepRecord> steps) {
            return new TransferResponse(t.id(), t.fromWalletId(), t.toWalletId(), new Amount(t.amount(), t.currency()),
                    t.state().dbValue(), t.failureReason().isEmpty() ? null : t.failureReason(),
                    t.createdAt(), t.updatedAt(),
                    steps == null ? null : steps.stream()
                            .map(s -> new StepResponse(s.step(), s.outcome(), s.detail(), s.at())).toList());
        }
    }

    private final TransferService service;
    private final TransferRepository transfers;

    TransferController(TransferService service, TransferRepository transfers) {
        this.service = service;
        this.transfers = transfers;
    }

    /**
     * 202 Accepted, not 201 Created: we've recorded the transfer, but the money
     * hasn't moved yet. {@code Location} says where to watch it finish.
     */
    @PostMapping
    ResponseEntity<TransferResponse> create(Caller caller, @RequestBody TransferRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var accepted = service.create(caller.userId(), key, body.from(), body.to(), body.amount(), body.currency());
        var response = ResponseEntity.accepted().location(URI.create("/v1/transfers/" + accepted.transfer().id()));
        if (accepted.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(TransferResponse.of(accepted.transfer(), null));
    }

    /** Where the saga is, and every step it took to get there. */
    @GetMapping("/{id}")
    TransferResponse get(Caller caller, @PathVariable String id) {
        UUID transferId;
        try {
            transferId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new dev.payments.transfer.error.NotFoundException("transfer not found");
        }
        Transfer t = service.get(caller.userId(), transferId);
        return TransferResponse.of(t, transfers.steps(t.id()));
    }
}
