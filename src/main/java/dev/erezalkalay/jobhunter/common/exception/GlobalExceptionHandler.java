package dev.erezalkalay.jobhunter.common.exception;

import dev.erezalkalay.jobhunter.auth.EmailAlreadyRegisteredException;
import dev.erezalkalay.jobhunter.auth.ratelimit.RateLimitExceededException;
import dev.erezalkalay.jobhunter.common.crypto.EncryptionNotConfiguredException;
import dev.erezalkalay.jobhunter.common.crypto.StoredSecretUnreadableException;
import dev.erezalkalay.jobhunter.jobdata.ImportConflictException;
import dev.erezalkalay.jobhunter.jobdata.ImportTooLargeException;
import dev.erezalkalay.jobhunter.jobdata.ImportValidationException;
import dev.erezalkalay.jobhunter.jobmatch.EvaluationInputsChangedException;
import dev.erezalkalay.jobhunter.jobmatch.ai.AiProviderException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into the {@link ApiError} body. Spring MVC's own client
 * errors (unknown route, wrong method or Content-Type, missing parameter or
 * multipart part, unconvertible path variable, ...) keep the 4xx status Spring
 * assigns them via {@link ResponseEntityExceptionHandler}, instead of falling
 * into the catch-all 500 below.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String UNEXPECTED_ERROR = "An unexpected error occurred";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(400, "Bad Request", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiError> handleFieldValidation(FieldValidationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.ofFieldErrors(400, "Bad Request", request.getRequestURI(), ex.getErrors()));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(Instant.now(), 409, "Conflict", ex.getMessage(),
                null, request.getRequestURI(), null, List.of(new ApiError.FieldError("email", ex.getMessage()))));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimited(RateLimitExceededException ex, HttpServletRequest request) {
        int retryAfter = ex.getRetryAfterSeconds();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(ApiError.withCode(429, "Too Many Requests", ex.getMessage(), "RATE_LIMITED",
                        request.getRequestURI(), retryAfter));
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalStatusTransition(IllegalStatusTransitionException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler({DuplicateJobException.class, EvaluationInputsChangedException.class, ImportConflictException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * Only a unique-key violation is a genuine conflict with existing data
     * (409). A value the column cannot hold (e.g. too long) is a bad request
     * (400). Any other constraint failure means the application wrote
     * something it validates elsewhere — a server bug, so a sanitized 500.
     * Classification uses Hibernate's portable constraint kind, not SQL text.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        if (isUniqueViolation(ex)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of(409, "Conflict", "The request conflicts with existing data", request.getRequestURI()));
        }
        if (hasCause(ex, DataException.class)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(400, "Bad Request",
                    "A value in the request is too long or has an invalid format", request.getRequestURI()));
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiError> handleAiProviderFailure(AiProviderException ex, HttpServletRequest request) {
        // Provider, model and HTTP status were logged where the call failed;
        // the message is the fixed, user-safe text for the error code.
        log.warn("AI provider call failed: {} ({})", ex.getCode(), request.getRequestURI());
        HttpStatus status = ex.getCode().httpStatus();
        Integer retryAfter = ex.getRetryAfterSeconds();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return response.body(ApiError.withCode(status.value(), status.getReasonPhrase(), ex.getMessage(),
                ex.getCode().name(), request.getRequestURI(), retryAfter));
    }

    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<ApiError> handleImportValidation(ImportValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = ex.getErrors().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(100)
                .map(e -> new ApiError.FieldError(e.getKey(), e.getValue()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(
                Instant.now(), 400, "Bad Request", ex.getMessage(), null, request.getRequestURI(), null, errors));
    }

    @ExceptionHandler(ImportTooLargeException.class)
    public ResponseEntity<ApiError> handleImportTooLarge(ImportTooLargeException ex, HttpServletRequest request) {
        return ResponseEntity.status(413)
                .body(ApiError.of(413, "Content Too Large", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(EncryptionNotConfiguredException.class)
    public ResponseEntity<ApiError> handleEncryptionNotConfigured(EncryptionNotConfiguredException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(503, "Service Unavailable", ex.getMessage(), request.getRequestURI()));
    }

    // Same family as "Configure an API key in Settings" (400): the user fixes it by entering the key again.
    @ExceptionHandler(StoredSecretUnreadableException.class)
    public ResponseEntity<ApiError> handleStoredSecretUnreadable(StoredSecretUnreadableException ex, HttpServletRequest request) {
        log.warn("A stored AI API key could not be decrypted ({})", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(400, "Bad Request", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Internal Server Error", UNEXPECTED_ERROR, request.getRequestURI()));
    }

    // --- Spring MVC exceptions (ResponseEntityExceptionHandler) ---

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.ofFieldErrors(400, "Bad Request", path(request), errors));
    }

    // Unparseable body or a value of the wrong JSON type (e.g. a string where a number is expected).
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(400, "Bad Request", "Malformed request body", path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(400, "Bad Request", "Uploaded file is too large", path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled exception processing {}", path(request), ex);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /**
     * Same {@link ApiError} shape as every other error, keeping Spring's
     * status and headers (e.g. {@code Allow} on 405). Client errors carry
     * Spring's short detail ("Request method 'GET' is not supported.");
     * server errors only the generic message.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status != null ? status.getReasonPhrase() : "Error";
        String message = UNEXPECTED_ERROR;
        if (statusCode.is4xxClientError()) {
            message = body instanceof ProblemDetail problem && problem.getDetail() != null ? problem.getDetail() : reason;
        }
        return new ResponseEntity<>(ApiError.of(statusCode.value(), reason, message, path(request)), headers, statusCode);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }

    private static boolean isUniqueViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
