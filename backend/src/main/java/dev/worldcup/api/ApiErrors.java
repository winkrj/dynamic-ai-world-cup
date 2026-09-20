package dev.worldcup.api;

import dev.worldcup.shared.Failure;
import dev.worldcup.shared.Failure.Code;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.OptionalLong;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    public static int status(Code code) {
        return switch (code) {
            case INVALID_INPUT -> 400;
            case NOT_FOUND -> 404;
            case VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, OPERATION_IN_PROGRESS, REGENERATION_EXHAUSTED, ALREADY_FROZEN, SESSION_NOT_COMPLETED -> 409;
            case QUALITY_GATE_FAILED, CLARIFICATION_REQUIRED, GROUNDING_REQUIRED, UNSUPPORTED_REQUEST, INVALID_SELECTION -> 422;
            case RATE_LIMITED -> 429;
            case PROVIDER_UNAVAILABLE -> 503;
            case INTERNAL_ERROR -> 500;
        };
    }
    public static ApiModels.Error body(Code code, String requestId) {
        String message = switch (code) {
            case INVALID_INPUT -> "Check the required fields, JSON types and request headers.";
            case NOT_FOUND -> "The requested resource is unavailable.";
            case VERSION_CONFLICT -> "Refresh the preview and use its current version.";
            case IDEMPOTENCY_CONFLICT -> "Use the same request body for this idempotency key.";
            case OPERATION_IN_PROGRESS -> "Wait for the current operation to finish.";
            case REGENERATION_EXHAUSTED -> "The successful regeneration has already been used.";
            case ALREADY_FROZEN -> "This bracket has already started and cannot be changed.";
            case QUALITY_GATE_FAILED -> "No candidate set passed validation. Revise the request or try again.";
            case CLARIFICATION_REQUIRED -> "Clarify the concern before generating candidates.";
            case GROUNDING_REQUIRED -> "Named places and current facts are not supported. Request general activity ideas instead.";
            case UNSUPPORTED_REQUEST -> "This request cannot be handled safely.";
            case RATE_LIMITED -> "The generation allowance is currently exhausted. Try later.";
            case PROVIDER_UNAVAILABLE -> "Candidate generation is unavailable. Try later.";
            case INVALID_SELECTION -> "Check the sequence, current pair and elapsed time of each selection.";
            case SESSION_NOT_COMPLETED -> "Complete the session before sharing.";
            case INTERNAL_ERROR -> "The request could not be completed. Try later.";
        };
        boolean retryable = code == Code.PROVIDER_UNAVAILABLE || code == Code.RATE_LIMITED || code == Code.OPERATION_IN_PROGRESS || code == Code.INTERNAL_ERROR;
        return new ApiModels.Error(code.name(), message, requestId, retryable);
    }
    @ExceptionHandler(Failure.class)
    ResponseEntity<ApiModels.Error> business(Failure failure, HttpServletRequest request) {
        return response(failure.code(), failure.retryAfterSeconds(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class, ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiModels.Error> invalid(Exception failure, HttpServletRequest request) { return response(Code.INVALID_INPUT, request); }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiModels.Error> missing(Exception failure, HttpServletRequest request) { return response(Code.NOT_FOUND, request); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiModels.Error> methodNotAllowed(HttpRequestMethodNotSupportedException failure, HttpServletRequest request) {
        var response = ResponseEntity.status(405);
        if (failure.getSupportedHttpMethods() != null) response.allow(failure.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new));
        return response.body(body(Code.INVALID_INPUT, (String) request.getAttribute(ApiGuard.REQUEST_ID)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiModels.Error> database(Exception failure, HttpServletRequest request) { return response(Code.PROVIDER_UNAVAILABLE, request); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.Error> unexpected(Exception failure, HttpServletRequest request) { return response(Code.INTERNAL_ERROR, request); }
    private ResponseEntity<ApiModels.Error> response(Code code, HttpServletRequest request) {
        return response(code, OptionalLong.empty(), request);
    }
    private ResponseEntity<ApiModels.Error> response(Code code, OptionalLong retryAfterSeconds, HttpServletRequest request) {
        var builder = ResponseEntity.status(status(code));
        retryAfterSeconds.ifPresent(seconds -> builder.header("Retry-After", Long.toString(seconds)));
        return builder.body(body(code, (String) request.getAttribute(ApiGuard.REQUEST_ID)));
    }
}
