package com.ledgerguard.fixture;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Hidden
@RestController
@RequestMapping("/api/ops")
public class TestOpsController {

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, String>> getOpsDashboard() {
        return ResponseEntity.ok(Map.of("status", "OPS_OK"));
    }
}
