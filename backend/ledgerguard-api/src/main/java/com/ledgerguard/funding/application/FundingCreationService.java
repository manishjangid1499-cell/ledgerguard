package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import com.ledgerguard.idempotency.application.IdempotencyService;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.ledger.domain.LedgerAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional boundary for durable creation and idempotency registration of a FundingOperation.
 * <p>
 * Ensures that the initial PROCESSING funding record is committed to PostgreSQL before any external
 * network call to the PSP simulator is made.
 */
@Service
public class FundingCreationService {

    private static final Logger log = LoggerFactory.getLogger(FundingCreationService.class);

    private final IdempotencyService idempotencyService;
    private final FundingOperationRepository fundingOperationRepository;

    public FundingCreationService(
            IdempotencyService idempotencyService,
            FundingOperationRepository fundingOperationRepository
    ) {
        this.idempotencyService = idempotencyService;
        this.fundingOperationRepository = fundingOperationRepository;
    }

    public record CreationOutcome(FundingOperation funding, boolean replayed) {
    }

    /**
     * Atomically registers idempotency and creates a durable PROCESSING FundingOperation if not already created.
     *
     * @param command validated funding command
     * @param customerAccount customer ledger account
     * @param fingerprint canonical request fingerprint
     * @return CreationOutcome containing the durable funding operation and replay flag
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CreationOutcome createOrGetFunding(
            CreateFundingCommand command,
            LedgerAccount customerAccount,
            RequestFingerprint fingerprint
    ) {
        Objects.requireNonNull(command, "CreateFundingCommand must not be null");
        Objects.requireNonNull(customerAccount, "Customer ledger account must not be null");
        Objects.requireNonNull(fingerprint, "RequestFingerprint must not be null");

        IdempotencyCommand idempotencyCommand = IdempotencyCommand.of(
                command.actorUserId(),
                FundingService.OPERATION_NAMESPACE,
                command.idempotencyKey(),
                fingerprint
        );

        IdempotencyExecutionResult result = idempotencyService.execute(idempotencyCommand, () -> {
            UUID fundingId = UUID.randomUUID();
            FundingOperation funding = new FundingOperation(
                    fundingId,
                    command.actorUserId(),
                    customerAccount.getId(),
                    command.amount().getMinorUnits(),
                    command.amount().getCurrencyCode(),
                    Instant.now()
            );
            fundingOperationRepository.saveAndFlush(funding);
            log.info("Created durable PROCESSING FundingOperation: id={}, actor={}, amount={}",
                    fundingId, command.actorUserId(), command.amount().getMinorUnits());
            return fundingId;
        });

        FundingOperation funding = fundingOperationRepository.findById(result.resultId())
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found for ID: " + result.resultId()));

        return new CreationOutcome(funding, result.replayed());
    }
}
