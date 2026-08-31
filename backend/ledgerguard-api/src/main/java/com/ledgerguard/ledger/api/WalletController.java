package com.ledgerguard.ledger.api;

import com.ledgerguard.ledger.application.WalletQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for authenticated user wallet balance and details lookup.
 */
@RestController
@RequestMapping(value = "/api/wallets", produces = MediaType.APPLICATION_JSON_VALUE)
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    /**
     * Retrieves the authenticated CUSTOMER or MERCHANT's owned wallet and posted balance snapshot.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<WalletResponse> getMyWallet(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return walletQueryService.findWalletByUserId(userId)
                .map(WalletResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
