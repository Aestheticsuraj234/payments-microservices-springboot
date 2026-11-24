package dev.payments.wallet.web;

import dev.payments.wallet.error.UnauthenticatedException;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the {@code X-User-ID} header into a {@link Caller} argument.
 *
 * <p>The wallet doesn't check tokens: the gateway already did, and it sets
 * this header from the token's subject, after deleting any copy the client
 * sent. The wallet trusts the header because only the gateway can reach it:
 * that is a network rule (in Compose, and in Kubernetes with a network policy),
 * and later, mTLS between services makes it a cryptographic one.
 *
 * <p>No header means the request didn't come through the gateway: refuse it.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-ID";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        String header = request.getHeader(HEADER);
        if (header == null) {
            throw new UnauthenticatedException("no authenticated caller");
        }
        try {
            return new Caller(UUID.fromString(header));
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException("no authenticated caller");
        }
    }
}
