package dev.payments.auth.security;

import static org.springframework.security.config.Customizer.withDefaults;

import dev.payments.auth.web.ProblemAuthenticationEntryPoint;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /**
     * A stateless API: no sessions, no cookies, so no CSRF to defend against.
     * Clients prove who they are with a bearer token on every request.
     * Registration, login, refresh and logout are public by nature; only
     * {@code /v1/auth/me} needs a valid access token.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint) {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/auth/me").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs.jwt(withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    /**
     * argon2id: memory-hard, so guessing passwords on a GPU is expensive. The
     * encoded hash carries its own parameters and salt (the PHC string format).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
