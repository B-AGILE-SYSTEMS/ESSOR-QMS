package com.essor.qms.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Format d'erreur commun de l'API : {@code {"error","message","details"}}. */
@RestControllerAdvice
class ApiErrorHandler {

    record ApiError(String error, String message, Map<String, Object> details) {
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(f.getField(), f.getDefaultMessage());
        }
        return bad("Données invalides", Map.of("fields", fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParams(HandlerMethodValidationException e) {
        return bad("Paramètres invalides", Map.of());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ApiError> violation(jakarta.validation.ConstraintViolationException e) {
        return bad("Paramètres invalides", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException e) {
        return bad("Paramètre invalide : " + e.getName(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return bad("Corps de requête illisible", Map.of());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("too_large", "Fichier trop volumineux (10 Mo maximum)", Map.of()));
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ApiError> missingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        return bad("Fichier manquant (champ multipart file)", Map.of());
    }

    private static ResponseEntity<ApiError> bad(String message, Map<String, Object> details) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("invalid", message, details));
    }
}
