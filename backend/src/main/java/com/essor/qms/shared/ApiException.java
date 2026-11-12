package com.essor.qms.shared;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Erreur métier renvoyée au client au format {@code {"error":"code","message":"texte français","details":{}}}.
 * Le message ne contient jamais de donnée personnelle.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " introuvable");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
