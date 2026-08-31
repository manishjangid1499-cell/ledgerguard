package com.ledgerguard.fixture;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only REST controller fixture used strictly to test validation, parsing,
 * and error-handling behavior during automated testing.
 */
@RestController
@RequestMapping("/api/test")
public class TestErrorController {

    public record TestRequest(
            @NotBlank(message = "must not be blank") String name,
            @Min(value = 1, message = "must be greater than or equal to 1") int amount
    ) {}

    @PostMapping("/validate")
    public ResponseEntity<Map<String, String>> testValidation(@Valid @RequestBody TestRequest request) {
        return ResponseEntity.ok(Map.of("status", "VALID", "name", request.name()));
    }

    @GetMapping("/server-error")
    public ResponseEntity<Void> testServerError() {
        throw new RuntimeException("Simulated sensitive database password failure");
    }
}
