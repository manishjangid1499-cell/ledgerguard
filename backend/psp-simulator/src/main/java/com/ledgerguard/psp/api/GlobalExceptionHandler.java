package com.ledgerguard.psp.api;

import com.ledgerguard.psp.application.ConflictingReplayException;
import com.ledgerguard.psp.application.InvalidOperationException;
import com.ledgerguard.psp.application.OperationNotFoundException;
import com.ledgerguard.psp.application.TemporaryFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TemporaryFailureException.class)
    public ProblemDetail handleTemporaryFailure(TemporaryFailureException e) {
        log.warn("Temporary provider failure: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        problem.setTitle("Temporary Provider Failure");
        problem.setType(URI.create("urn:ledgerguard:psp:error:temporary-failure"));
        return problem;
    }

    @ExceptionHandler(ConflictingReplayException.class)
    public ProblemDetail handleConflictingReplay(ConflictingReplayException e) {
        log.warn("Conflicting replay: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflicting Operation Replay");
        problem.setType(URI.create("urn:ledgerguard:psp:error:conflicting-replay"));
        return problem;
    }

    @ExceptionHandler(InvalidOperationException.class)
    public ProblemDetail handleInvalidOperation(InvalidOperationException e) {
        log.warn("Invalid operation: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Operation Request");
        problem.setType(URI.create("urn:ledgerguard:psp:error:invalid-operation"));
        return problem;
    }

    @ExceptionHandler(OperationNotFoundException.class)
    public ProblemDetail handleOperationNotFound(OperationNotFoundException e) {
        log.warn("Operation not found: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Operation Not Found");
        problem.setType(URI.create("urn:ledgerguard:psp:error:not-found"));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation error: {}", detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("urn:ledgerguard:psp:error:validation-failed"));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception e) {
        log.error("Unhandled exception in PSP simulator", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An internal provider error occurred");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:ledgerguard:psp:error:internal"));
        return problem;
    }
}
