package com.ledgerguard.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
