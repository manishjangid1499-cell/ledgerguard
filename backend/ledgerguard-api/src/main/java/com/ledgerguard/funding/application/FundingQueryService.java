package com.ledgerguard.funding.application;

import com.ledgerguard.funding.api.FundingReadResponse;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.shared.api.QueryPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FundingQueryService {
    private final FundingOperationRepository repository;

    public FundingQueryService(FundingOperationRepository repository) {
        this.repository = repository;
    }

    public PagedResponse<FundingReadResponse> list(UUID userId, int page, int size) {
        return QueryPagination.response(repository.findByInitiatedByUserId(userId,
                QueryPagination.newestFirst(page, size)).map(FundingReadResponse::from));
    }

    public Optional<FundingReadResponse> detail(UUID userId, UUID id) {
        return repository.findByIdAndInitiatedByUserId(id, userId).map(FundingReadResponse::from);
    }
}
