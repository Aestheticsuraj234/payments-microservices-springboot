package dev.payments.gateway;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** A 401 in the same Problem Details shape every service uses. */
@Component
class ProblemEntryPoint implements ServerAuthenticationEntryPoint {

    private final JsonMapper json;

    ProblemEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "a valid access token is required");
        problem.setProperty("code", "unauthenticated");
        problem.setProperty("request_id", exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER));
        byte[] body = json.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
