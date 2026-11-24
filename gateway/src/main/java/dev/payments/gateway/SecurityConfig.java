package dev.payments.gateway;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Authentication at the edge. Every request needs a valid access token,
 * except the few that exist to get one. Downstream services trust the
 * gateway's verdict instead of each checking tokens again.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /** Routes anyone may call: you can't have a token before you log in. */
    static final String[] PUBLIC = {
            "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout",
            "/actuator/health/**",
            // Where the circuit breaker forwards failed requests: always answerable.
            FallbackController.PATH
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemEntryPoint entryPoint) {
        return http
                .csrf(csrf -> csrf.disable())          // bearer tokens, no cookies: nothing to forge
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .cors(withDefaults())                  // answers browser preflights before auth runs
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(PUBLIC).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    /**
     * Verifies access tokens with the auth service's published keys. The keys
     * are fetched on first use and cached, so the gateway can start before the
     * auth service, and checks tokens without calling it on every request.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience()))));
        return decoder;
    }

    /** Which web pages (origins) may call the API from a browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-ID"));
        cors.setExposedHeaders(List.of("X-Request-ID", "Idempotent-Replayed", "Location"));
        cors.setMaxAge(Duration.ofHours(1));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
