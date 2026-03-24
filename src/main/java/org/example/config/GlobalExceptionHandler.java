package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST API.
 * Provides consistent error responses and security-aware error handling.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    /**
     * Handle validation errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Validation failed");
        response.put("errors", errors);
        
        logger.debug("Validation error: {}", errors);
        return ResponseEntity.badRequest().body(response);
    }
    
    /**
     * Handle IllegalArgumentException (business logic errors).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", ex.getMessage());
        
        logger.debug("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }
    
    /**
     * MongoDB duplicate key (e.g. unique index on username).
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(DuplicateKeyException ex) {
        logger.warn("Duplicate key: {}", ex.getMostSpecificCause().getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("error", "This value already exists. It may be a duplicate username.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handle IllegalStateException (state-related errors).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", ex.getMessage());
        
        logger.debug("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
    
    /**
     * Missing static resource (e.g. wrong path) — not a server bug; return 404 without ERROR noise.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        logger.debug("Not found: {}", ex.getResourcePath());
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handle generic exceptions (don't expose internal details).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        logger.error("Unexpected error", ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "An unexpected error occurred. Please try again later.");
        
        // SECURITY: Don't expose internal error details to clients
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}

