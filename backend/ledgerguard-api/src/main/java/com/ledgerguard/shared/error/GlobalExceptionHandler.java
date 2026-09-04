package com.ledgerguard.shared.error;

import com.ledgerguard.identity.application.EmailAlreadyRegisteredException;
import com.ledgerguard.identity.application.ForbiddenRegistrationException;
import com.ledgerguard.identity.application.InvalidCredentialsException;
import com.ledgerguard.identity.application.InvalidPasswordException;
import com.ledgerguard.identity.application.InvalidRefreshTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Centralized global exception handler mapping application and framework exceptions
 * into standardized RFC 9457 ProblemDetail representations.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROP_ERROR_CODE = "errorCode";
    private static final String PROP_TIMESTAMP = "timestamp";
    private static final String PROP_ERRORS = "errors";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<ValidationErrorDetail> validationErrors = ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(error -> {
                    String fieldName = (error instanceof FieldError fieldError)
                            ? fieldError.getField()
                            : error.getObjectName();
                    String message = (error.getDefaultMessage() != null)
                            ? error.getDefaultMessage()
                            : "Invalid value";
                    return new ValidationErrorDetail(fieldName, message);
                })
                .sorted(Comparator.comparing(ValidationErrorDetail::field))
                .toList();

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid."
        );
        problemDetail.setTitle("Validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.VALIDATION_FAILED, request);
        problemDetail.setProperty(PROP_ERRORS, validationErrors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        log.warn("Malformed HTTP request payload: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request body is malformed or could not be parsed."
        );
        problemDetail.setTitle("Malformed request");
        enrichProblemDetail(problemDetail, ApiErrorCode.MALFORMED_REQUEST, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The requested resource was not found."
        );
        problemDetail.setTitle("Resource not found");
        enrichProblemDetail(problemDetail, ApiErrorCode.RESOURCE_NOT_FOUND, request);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex, WebRequest request) {
        log.warn("Registration failed: email already registered");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Email is already registered."
        );
        problemDetail.setTitle("Email already registered");
        enrichProblemDetail(problemDetail, ApiErrorCode.EMAIL_ALREADY_REGISTERED, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Database constraint violation during request execution");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Email is already registered."
        );
        problemDetail.setTitle("Email already registered");
        enrichProblemDetail(problemDetail, ApiErrorCode.EMAIL_ALREADY_REGISTERED, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(ForbiddenRegistrationException.class)
    public ResponseEntity<ProblemDetail> handleForbiddenRegistration(ForbiddenRegistrationException ex, WebRequest request) {
        log.warn("Registration rejected: attempt to register with forbidden role");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Registration with OPS role is not permitted."
        );
        problemDetail.setTitle("Validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.VALIDATION_FAILED, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPassword(InvalidPasswordException ex, WebRequest request) {
        log.warn("Registration rejected: password does not meet security policy");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.VALIDATION_FAILED, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex, WebRequest request) {
        log.warn("Authentication failed: invalid credentials");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password."
        );
        problemDetail.setTitle("Unauthorized");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_CREDENTIALS, request);

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ex, WebRequest request) {
        log.warn("Refresh failed: invalid refresh token");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Invalid, expired, or revoked refresh token."
        );
        problemDetail.setTitle("Unauthorized");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_REFRESH_TOKEN, request);

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.idempotency.domain.IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(com.ledgerguard.idempotency.domain.IdempotencyConflictException ex, WebRequest request) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problemDetail.setTitle("Idempotency conflict");
        enrichProblemDetail(problemDetail, ApiErrorCode.IDEMPOTENCY_CONFLICT, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.idempotency.domain.IdempotencyOperationInProgressException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyInProgress(com.ledgerguard.idempotency.domain.IdempotencyOperationInProgressException ex, WebRequest request) {
        log.warn("Idempotency operation in progress: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problemDetail.setTitle("Operation in progress");
        enrichProblemDetail(problemDetail, ApiErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.transfer.domain.InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(com.ledgerguard.transfer.domain.InsufficientFundsException ex, WebRequest request) {
        log.warn("Transfer rejected due to insufficient funds: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Insufficient funds for this transfer."
        );
        problemDetail.setTitle("Insufficient funds");
        enrichProblemDetail(problemDetail, ApiErrorCode.INSUFFICIENT_FUNDS, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.transfer.domain.TransferDestinationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTransferDestinationNotFound(com.ledgerguard.transfer.domain.TransferDestinationNotFoundException ex, WebRequest request) {
        log.warn("Transfer destination not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problemDetail.setTitle("Resource not found");
        enrichProblemDetail(problemDetail, ApiErrorCode.RESOURCE_NOT_FOUND, request);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.transfer.domain.TransferValidationException.class)
    public ResponseEntity<ProblemDetail> handleTransferValidation(com.ledgerguard.transfer.domain.TransferValidationException ex, WebRequest request) {
        log.warn("Transfer validation rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Transfer validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_TRANSFER, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.payment.domain.PaymentDestinationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentDestinationNotFound(com.ledgerguard.payment.domain.PaymentDestinationNotFoundException ex, WebRequest request) {
        log.warn("Payment destination not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problemDetail.setTitle("Resource not found");
        enrichProblemDetail(problemDetail, ApiErrorCode.RESOURCE_NOT_FOUND, request);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.payment.domain.PaymentValidationException.class)
    public ResponseEntity<ProblemDetail> handlePaymentValidation(com.ledgerguard.payment.domain.PaymentValidationException ex, WebRequest request) {
        log.warn("Payment validation rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Payment validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_PAYMENT, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.payment.domain.PlatformFeeAccountException.class)
    public ResponseEntity<ProblemDetail> handlePlatformFeeAccountException(com.ledgerguard.payment.domain.PlatformFeeAccountException ex, WebRequest request) {
        log.error("Platform fee account configuration error: {}", ex.getMessage(), ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal platform configuration error."
        );
        problemDetail.setTitle("Internal Server Error");
        enrichProblemDetail(problemDetail, ApiErrorCode.INTERNAL_ERROR, request);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.refund.domain.RefundLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRefundLimitExceeded(com.ledgerguard.refund.domain.RefundLimitExceededException ex, WebRequest request) {
        log.warn("Refund limit exceeded: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Refund amount exceeds the remaining refundable amount."
        );
        problemDetail.setTitle("Refund limit exceeded");
        enrichProblemDetail(problemDetail, ApiErrorCode.REFUND_LIMIT_EXCEEDED, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.refund.domain.PaymentNotRefundableException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotRefundable(com.ledgerguard.refund.domain.PaymentNotRefundableException ex, WebRequest request) {
        log.warn("Payment not refundable: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problemDetail.setTitle("Payment not refundable");
        enrichProblemDetail(problemDetail, ApiErrorCode.PAYMENT_NOT_REFUNDABLE, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.ledger.application.LedgerPostingException.class)
    public ResponseEntity<ProblemDetail> handleLedgerPostingException(com.ledgerguard.ledger.application.LedgerPostingException ex, WebRequest request) {
        log.warn("Ledger posting rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Ledger posting failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_TRANSFER, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.funding.domain.FundingValidationException.class)
    public ResponseEntity<ProblemDetail> handleFundingValidation(com.ledgerguard.funding.domain.FundingValidationException ex, WebRequest request) {
        log.warn("Funding validation rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Funding validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_FUNDING, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.payout.domain.PayoutValidationException.class)
    public ResponseEntity<ProblemDetail> handlePayoutValidation(com.ledgerguard.payout.domain.PayoutValidationException ex, WebRequest request) {
        log.warn("Payout validation rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Payout validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_PAYOUT, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.funding.domain.PspClearingAccountException.class)
    public ResponseEntity<ProblemDetail> handlePspClearingAccountException(com.ledgerguard.funding.domain.PspClearingAccountException ex, WebRequest request) {
        log.error("PSP clearing account configuration error: {}", ex.getMessage(), ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal platform configuration error."
        );
        problemDetail.setTitle("Internal Server Error");
        enrichProblemDetail(problemDetail, ApiErrorCode.INTERNAL_ERROR, request);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex, WebRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access denied: insufficient permissions to perform this operation."
        );
        problemDetail.setTitle("Access denied");
        enrichProblemDetail(problemDetail, ApiErrorCode.ACCESS_DENIED, request);

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.provider.application.ProviderAuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleProviderAuthentication(com.ledgerguard.provider.application.ProviderAuthenticationException ex, WebRequest request) {
        log.warn("Provider webhook authentication failed");
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Webhook authentication failed."
        );
        problemDetail.setTitle("Unauthorized");
        enrichProblemDetail(problemDetail, ApiErrorCode.PROVIDER_AUTHENTICATION_FAILED, request);

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.provider.application.ProviderEventValidationException.class)
    public ResponseEntity<ProblemDetail> handleProviderEventValidation(com.ledgerguard.provider.application.ProviderEventValidationException ex, WebRequest request) {
        log.warn("Provider webhook payload validation failed: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.VALIDATION_FAILED, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.provider.application.ProviderEventConflictException.class)
    public ResponseEntity<ProblemDetail> handleProviderEventConflict(com.ledgerguard.provider.application.ProviderEventConflictException ex, WebRequest request) {
        log.warn("Provider webhook event conflict: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problemDetail.setTitle("Provider event conflict");
        enrichProblemDetail(problemDetail, ApiErrorCode.PROVIDER_EVENT_CONFLICT, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.reconciliation.domain.ReconciliationValidationException.class)
    public ResponseEntity<ProblemDetail> handleReconciliationValidation(com.ledgerguard.reconciliation.domain.ReconciliationValidationException ex, WebRequest request) {
        log.warn("Reconciliation validation rejected: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Reconciliation validation failed");
        enrichProblemDetail(problemDetail, ApiErrorCode.INVALID_RECONCILIATION_OPERATION, request);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.reconciliation.domain.ReconciliationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleReconciliationNotFound(com.ledgerguard.reconciliation.domain.ReconciliationNotFoundException ex, WebRequest request) {
        log.warn("Reconciliation resource not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problemDetail.setTitle("Reconciliation resource not found");
        enrichProblemDetail(problemDetail, ApiErrorCode.RESOURCE_NOT_FOUND, request);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(com.ledgerguard.reconciliation.domain.ReconciliationConflictException.class)
    public ResponseEntity<ProblemDetail> handleReconciliationConflict(com.ledgerguard.reconciliation.domain.ReconciliationConflictException ex, WebRequest request) {
        log.warn("Reconciliation conflict: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problemDetail.setTitle("Reconciliation conflict");
        enrichProblemDetail(problemDetail, ApiErrorCode.RECONCILIATION_CONFLICT, request);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandledException(Exception ex, WebRequest request) {
        log.error("Unhandled server exception: {}", ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred."
        );
        problemDetail.setTitle("Internal Server Error");
        enrichProblemDetail(problemDetail, ApiErrorCode.INTERNAL_ERROR, request);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    private void enrichProblemDetail(ProblemDetail problemDetail, String errorCode, WebRequest request) {
        problemDetail.setProperty(PROP_ERROR_CODE, errorCode);
        problemDetail.setProperty(PROP_TIMESTAMP, Instant.now());
        if (request instanceof ServletWebRequest servletWebRequest) {
            problemDetail.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
        }
    }
}
