package dev.payments.gateway;

import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells downstream services who the caller is, in the {@code X-User-ID}
 * header, taken from the verified token's subject.
 *
 * <p>First it <em>deletes</em> any {@code X-User-ID} the client sent. Without
 * that line, anyone could claim to be anyone: services trust this header
 * because only the gateway may set it.
 */
@Component
class IdentityFilter implements GlobalFilter, Ordered {

    static final String USER_HEADER = "X-User-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> Optional.of(((JwtAuthenticationToken) p).getToken().getSubject()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(user -> chain.filter(exchange.mutate()
                        .request(r -> r.headers(h -> {
                            h.remove(USER_HEADER);
                            user.ifPresent(id -> h.set(USER_HEADER, id));
                        }))
                        .build()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
