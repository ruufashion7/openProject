package org.example.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Service for password encoding and validation.
 * Uses BCrypt for secure password hashing.
 */
@Service
public class PasswordEncoderService {
    private final PasswordEncoder passwordEncoder;

    public PasswordEncoderService(@Value("${security.password.bcrypt-strength:12}") int bcryptStrength) {
        int strength = Math.clamp(bcryptStrength, 4, 31);
        this.passwordEncoder = new BCryptPasswordEncoder(strength);
    }
    
    // Password policy patterns
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");
    private static final Pattern SPECIAL_PATTERN = Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");
    
    /**
     * Encode a raw password.
     */
    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        return passwordEncoder.encode(rawPassword);
    }
    
    /**
     * Verify a raw password against an encoded password.
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
    
    /**
     * Validate password against security policy.
     * @param password Password to validate
     * @param minLength Minimum password length
     * @param requireUppercase Whether uppercase is required
     * @param requireLowercase Whether lowercase is required
     * @param requireDigit Whether digit is required
     * @param requireSpecial Whether special character is required
     * @return Validation result with error message if invalid
     */
    public PasswordValidationResult validatePassword(String password, 
                                                     int minLength,
                                                     boolean requireUppercase,
                                                     boolean requireLowercase,
                                                     boolean requireDigit,
                                                     boolean requireSpecial) {
        if (password == null || password.length() < minLength) {
            return PasswordValidationResult.invalid(
                String.format("Password must be at least %d characters long", minLength)
            );
        }
        
        if (requireUppercase && !UPPERCASE_PATTERN.matcher(password).matches()) {
            return PasswordValidationResult.invalid("Password must contain at least one uppercase letter");
        }
        
        if (requireLowercase && !LOWERCASE_PATTERN.matcher(password).matches()) {
            return PasswordValidationResult.invalid("Password must contain at least one lowercase letter");
        }
        
        if (requireDigit && !DIGIT_PATTERN.matcher(password).matches()) {
            return PasswordValidationResult.invalid("Password must contain at least one digit");
        }
        
        if (requireSpecial && !SPECIAL_PATTERN.matcher(password).matches()) {
            return PasswordValidationResult.invalid("Password must contain at least one special character");
        }
        
        // Check for common weak passwords
        if (isCommonPassword(password)) {
            return PasswordValidationResult.invalid("Password is too common. Please choose a stronger password");
        }
        
        return PasswordValidationResult.valid();
    }
    
    /**
     * Check if password is a common weak password.
     */
    private boolean isCommonPassword(String password) {
        String lower = password.toLowerCase();
        String[] commonPasswords = {
            "password", "123456", "12345678", "1234", "qwerty", "abc123",
            "password1", "admin", "letmein", "welcome", "monkey", "1234567"
        };
        for (String common : commonPasswords) {
            if (lower.contains(common)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Result of password validation.
     */
    public static class PasswordValidationResult {
        private final boolean valid;
        private final String errorMessage;
        
        private PasswordValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
        
        public static PasswordValidationResult valid() {
            return new PasswordValidationResult(true, null);
        }
        
        public static PasswordValidationResult invalid(String errorMessage) {
            return new PasswordValidationResult(false, errorMessage);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

