package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 28 â€” Resolution Note Raw Control Character Hardening Tests")
class ResolutionNoteValidationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationCaseManagementService managementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private User opsUser;

    @BeforeEach
    void setUp() {
        opsUser = userRepository.save(new User(UUID.randomUUID(), "opsNote." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
    }

    @ParameterizedTest(name = "Forbidden raw control character input: \"{0}\"")
    @ValueSource(strings = {
            "\rValid note",
            "Valid note\r",
            "\nValid note",
            "Valid note\n",
            "\tValid note",
            "Valid note\t",
            "\0Valid note",
            "Valid\0note",
            "Valid note\0",
            "Valid note\u007F",
            "\u001FValid note",
            "Valid\u0007note"
    })
    @DisplayName("Raw note containing C0 controls or DEL rejected with validation exception")
    void rawControlCharactersRejected(String badNote) {
        UUID caseId = seedCase();
        managementService.claimCase(caseId, opsUser.getId());

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsUser.getId(), badNote))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    @DisplayName("Null or blank note rejected with validation exception")
    void nullOrBlankRejected() {
        UUID caseId = seedCase();
        managementService.claimCase(caseId, opsUser.getId());

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsUser.getId(), null))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsUser.getId(), "    "))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("Note exceeding 1000 characters rejected")
    void oversizedNoteRejected() {
        UUID caseId = seedCase();
        managementService.claimCase(caseId, opsUser.getId());

        String longNote = "A".repeat(1001);
        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsUser.getId(), longNote))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("must not exceed 1000 characters");
    }

    @Test
    @DisplayName("Normal text with surrounding ordinary whitespace is normalized and accepted")
    void whitespaceNormalizedAndAccepted() {
        UUID caseId = seedCase();
        managementService.claimCase(caseId, opsUser.getId());

        ReconciliationCaseResponse resp = managementService.resolveManually(
                caseId, opsUser.getId(), "   Investigation completed successfully.   ");

        assertThat(resp.status()).isEqualTo("RESOLVED");
        assertThat(resp.resolutionNote()).isEqualTo("Investigation completed successfully.");
    }

    @Test
    @DisplayName("Unicode printable text with accents and symbols is accepted")
    void unicodePrintableAccepted() {
        UUID caseId = seedCase();
        managementService.claimCase(caseId, opsUser.getId());

        String unicodeNote = "Investigation: opÃ©ration confirmÃ©e pour 150.00 â‚¬ (ticket #42) â€” VÃ©rifiÃ© âœ“";
        ReconciliationCaseResponse resp = managementService.resolveManually(caseId, opsUser.getId(), unicodeNote);

        assertThat(resp.status()).isEqualTo("RESOLVED");
        assertThat(resp.resolutionNote()).isEqualTo(unicodeNote);
    }

    private UUID seedCase() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'PROVIDER_SETTLEMENT', 'PROVIDER_NOT_FOUND', 'FUNDING_OPERATION', ?, 'test desc', NOW())",
                itemId, runId, UUID.randomUUID());

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}