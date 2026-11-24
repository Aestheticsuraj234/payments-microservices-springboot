package dev.payments.auth.user;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
class AuthController {

    record Credentials(@Nullable String email, @Nullable String password) {
    }

    record RefreshRequest(@Nullable String refreshToken) {
    }

    record UserResponse(UUID id, String email, Instant createdAt) {
        static UserResponse of(User u) {
            return new UserResponse(u.id(), u.email(), u.createdAt());
        }
    }

    /** The OAuth 2 token response shape, which clients and libraries already know. */
    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {
        static TokenResponse of(Tokens t) {
            return new TokenResponse(t.accessToken(), "Bearer", t.expiresIn().toSeconds(), t.refreshToken());
        }
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@RequestBody Credentials body) {
        return UserResponse.of(auth.register(body.email(), body.password()));
    }

    @PostMapping("/login")
    TokenResponse login(@RequestBody Credentials body) {
        return TokenResponse.of(auth.login(body.email(), body.password()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@RequestBody RefreshRequest body) {
        return TokenResponse.of(auth.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody RefreshRequest body) {
        auth.logout(body.refreshToken());
    }

    /** The user the access token belongs to. Spring Security has already checked the token. */
    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(auth.user(UUID.fromString(jwt.getSubject())));
    }
}
