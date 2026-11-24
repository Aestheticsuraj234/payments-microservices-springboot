package dev.payments.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request an ID at the front door and passes it on. The services
 * behind the gateway log the same ID, so one request can be followed through
 * all of them. (Chapter 11 replaces this with distributed tracing.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter implements WebFilter {

    static final String HEADER = "X-Request-ID";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        String requestId = id;
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, requestId)))
                .build());
    }
}
