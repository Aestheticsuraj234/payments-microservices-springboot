package dev.payments.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * What a client gets when a service can't be reached, or its circuit breaker
 * is open: a quick, honest 503 in our usual error shape, with a hint when to
 * retry, instead of a hanging connection or a bare 502.
 */
@RestController
class FallbackController {

    static final String PATH = "/fallback/unavailable";

    @RequestMapping(PATH)
    ResponseEntity<ProblemDetail> unavailable(ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "the service is not available, try again later");
        problem.setProperty("code", "unavailable");
        problem.setProperty("request_id", exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(problem);
    }
}
