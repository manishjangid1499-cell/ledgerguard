package com.ledgerguard.payout.application;

import com.ledgerguard.payout.api.PayoutReadResponse;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.shared.api.QueryPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PayoutQueryService {
    private final PayoutRepository repository;

    public PayoutQueryService(PayoutRepository repository) {
        this.repository = repository;
    }

    public PagedResponse<PayoutReadResponse> list(UUID userId, int page, int size) {
        return QueryPagination.response(repository.findByInitiatedByUserId(userId,
                QueryPagination.newestFirst(page, size)).map(PayoutReadResponse::from));
    }

    public Optional<PayoutReadResponse> detail(UUID userId, UUID id) {
        return repository.findByIdAndInitiatedByUserId(id, userId).map(PayoutReadResponse::from);
    }
}
