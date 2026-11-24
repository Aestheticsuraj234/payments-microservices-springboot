package dev.payments.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, so one request's log lines can be found among
 * thousands, and a client reporting a problem can quote it.
 *
 * <p>The ID comes from the {@code X-Request-ID} header when a caller (later:
 * the gateway) already chose one, and is generated otherwise. It goes into the
 * logging MDC, so every log line written while handling the request carries
 * it, and back to the client in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    /** IDs we accept from callers: short, and safe to write into a log line. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled: a value left here would leak into the next request.
            MDC.remove(MDC_KEY);
        }
    }

    /** The ID of the request this thread is handling, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
