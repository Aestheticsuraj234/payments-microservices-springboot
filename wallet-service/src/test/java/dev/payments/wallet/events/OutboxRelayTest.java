package dev.payments.wallet.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static OutboxRelay.Message message(long seq) {
        return new OutboxRelay.Message(seq, UUID.randomUUID(), "wallet.events", "key", "{}");
    }

    @Test
    void sendsEverythingInOrder() {
        List<Long> seen = new ArrayList<>();
        var sent = OutboxRelay.sendInOrder(List.of(message(1), message(2), message(3)), m -> seen.add(m.seq()));
        assertThat(sent).containsExactly(1L, 2L, 3L);
        assertThat(seen).containsExactly(1L, 2L, 3L);
    }

    @Test
    void stopsAtTheFirstFailureSoNothingOvertakesIt() {
        List<Long> attempted = new ArrayList<>();
        var sent = OutboxRelay.sendInOrder(List.of(message(1), message(2), message(3)), m -> {
            attempted.add(m.seq());
            if (m.seq() == 2) {
                throw new IllegalStateException("broker unavailable");
            }
        });
        assertThat(sent).as("only 1 is marked published; 2 and 3 wait for the next poll").containsExactly(1L);
        assertThat(attempted).containsExactly(1L, 2L);
    }
}
