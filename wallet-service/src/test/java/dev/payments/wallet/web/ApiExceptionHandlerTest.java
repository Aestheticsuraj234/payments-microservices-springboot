package dev.payments.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.FieldErrors;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.error.RuleViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A web slice test: {@code @WebMvcTest} starts only the web layer (controllers,
 * advice, filters), not the whole application. The controller below exists only
 * in tests, to throw each kind of error.
 */
@WebMvcTest(controllers = ApiExceptionHandlerTest.ThrowingController.class)
@Import(ApiExceptionHandlerTest.ThrowingController.class)
class ApiExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("wallet not found");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("already exists");
        }

        @GetMapping("/test/invalid")
        void invalid() {
            new FieldErrors().add("amount", "must be positive").throwIfAny();
        }

        @GetMapping("/test/rule")
        void rule() {
            throw new RuleViolationException("insufficient_funds", "insufficient funds");
        }

        @GetMapping("/test/bug")
        void bug() {
            throw new IllegalStateException("password=hunter2 in the connection string");
        }

        record Body(@Positive long amount) {
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    void notFoundIs404() {
        assertThat(mvc.get().uri("/test/not-found"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("not_found"))
                .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("wallet not found"));
    }

    @Test
    void conflictIs409() {
        assertThat(mvc.get().uri("/test/conflict"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("conflict"));
    }

    @Test
    void validationIs422WithTheFields() {
        assertThat(mvc.get().uri("/test/invalid"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("invalid_input"))
                .hasPathSatisfying("$.fields.amount", v -> v.assertThat().isEqualTo("must be positive"));
    }

    @Test
    void beanValidationUsesTheSameShape() {
        assertThat(mvc.post().uri("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"amount\": -5}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("invalid_input"))
                .hasPathSatisfying("$.fields.amount", v -> v.assertThat().isNotNull());
    }

    @Test
    void ruleViolationsCarryTheirOwnCode() {
        assertThat(mvc.get().uri("/test/rule"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("insufficient_funds"));
    }

    @Test
    void bugsAre500AndLeakNothing() {
        var result = mvc.get().uri("/test/bug").exchange();
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("internal"));
        assertThat(result).bodyText().doesNotContain("hunter2");
    }

    @Test
    void malformedJsonIs400() {
        assertThat(mvc.post().uri("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("malformed_request"));
    }

    @Test
    void errorsCarryTheRequestId() {
        assertThat(mvc.get().uri("/test/not-found").header(RequestIdFilter.HEADER, "abc-123"))
                .hasHeader(RequestIdFilter.HEADER, "abc-123")
                .bodyJson().hasPathSatisfying("$.request_id", v -> v.assertThat().isEqualTo("abc-123"));
    }
}
