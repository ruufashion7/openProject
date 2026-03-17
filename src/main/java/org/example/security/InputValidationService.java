package org.example.security;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Service for validating and sanitizing input to prevent injection attacks.
 */
@Service
public class InputValidationService {
    
    // Patterns for validation
    private static final Pattern VALID_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,100}$");
    private static final Pattern VALID_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,500}$");
    private static final Pattern VALID_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,255}$");
    private static final Pattern VALID_TYPE_PATTERN = Pattern.compile("^(detailed|receivable)$");
    private static final Pattern VALID_DATE_PATTERN = Pattern.compile("^(old|new)$");
    private static final Pattern VALID_SIZE_PATTERN = Pattern.compile("^(80-90|95-100)$");
    private static final Pattern VALID_SORT_ORDER_PATTERN = Pattern.compile("^(asc|desc)$");
    
    /**
     * Validate MongoDB ID format.
     */
    public boolean isValidId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // MongoDB ObjectId is 24 hex characters, but we also accept custom IDs
        return VALID_ID_PATTERN.matcher(id).matches() && id.length() <= 100;
    }
    
    /**
     * Validate session token format.
     */
    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return VALID_TOKEN_PATTERN.matcher(token).matches() && token.length() <= 500;
    }
    
    /**
     * Validate filename to prevent path traversal.
     */
    public boolean isValidFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        // Check for path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        return VALID_FILENAME_PATTERN.matcher(filename).matches();
    }
    
    /**
     * Validate upload type.
     */
    public boolean isValidUploadType(String type) {
        if (type == null) {
            return false;
        }
        return VALID_TYPE_PATTERN.matcher(type.toLowerCase()).matches();
    }
    
    /**
     * Validate date value (old/new).
     */
    public boolean isValidDateValue(String date) {
        if (date == null) {
            return false;
        }
        return VALID_DATE_PATTERN.matcher(date.toLowerCase()).matches();
    }
    
    /**
     * Validate size value.
     */
    public boolean isValidSizeValue(String size) {
        if (size == null) {
            return false;
        }
        return VALID_SIZE_PATTERN.matcher(size).matches();
    }
    
    /**
     * Validate sort order.
     */
    public boolean isValidSortOrder(String sortOrder) {
        if (sortOrder == null) {
            return true; // Optional parameter
        }
        return VALID_SORT_ORDER_PATTERN.matcher(sortOrder.toLowerCase()).matches();
    }
    
    /**
     * Sanitize string input to prevent injection.
     */
    public String sanitizeString(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        
        // Remove null bytes
        String sanitized = input.replace("\0", "");
        
        // Trim and limit length
        sanitized = sanitized.trim();
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }
        
        return sanitized;
    }
    
    /**
     * Validate query parameter to prevent injection.
     */
    public String sanitizeQuery(String query, int maxLength) {
        if (query == null || query.isBlank()) {
            return "";
        }
        
        // Remove potentially dangerous characters
        String sanitized = query.replaceAll("[<>\"'%;()&+]", "");
        
        // Limit length
        return sanitizeString(sanitized, maxLength);
    }
    
    /**
     * Validate product name.
     */
    public boolean isValidProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            return false;
        }
        
        // Check length
        if (productName.length() > 200) {
            return false;
        }
        
        // Check for dangerous characters
        if (productName.contains("<") || productName.contains(">") || 
            productName.contains("\"") || productName.contains("'") ||
            productName.contains("\0")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validate numeric range.
     */
    public boolean isValidNumericRange(Number value, Number min, Number max) {
        if (value == null) {
            return false;
        }
        double doubleValue = value.doubleValue();
        return doubleValue >= min.doubleValue() && doubleValue <= max.doubleValue();
    }
    
    /**
     * Validate integer range.
     */
    public boolean isValidIntegerRange(Integer value, Integer min, Integer max) {
        if (value == null) {
            return false;
        }
        return value >= min && value <= max;
    }
}

