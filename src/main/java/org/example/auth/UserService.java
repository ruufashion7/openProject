package org.example.auth;

import org.example.security.PasswordEncoderService;
import org.example.security.SecurityAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoderService passwordEncoderService;
    private final SecurityAuditService securityAuditService;
    
    private final int minPasswordLength;
    private final boolean requireUppercase;
    private final boolean requireLowercase;
    private final boolean requireDigit;
    private final boolean requireSpecial;

    public UserService(UserRepository userRepository,
                      PasswordEncoderService passwordEncoderService,
                      SecurityAuditService securityAuditService,
                      @Value("${security.password.min-length:8}") int minPasswordLength,
                      @Value("${security.password.require-uppercase:true}") boolean requireUppercase,
                      @Value("${security.password.require-lowercase:true}") boolean requireLowercase,
                      @Value("${security.password.require-digit:true}") boolean requireDigit,
                      @Value("${security.password.require-special:true}") boolean requireSpecial) {
        this.userRepository = userRepository;
        this.passwordEncoderService = passwordEncoderService;
        this.securityAuditService = securityAuditService;
        this.minPasswordLength = minPasswordLength;
        this.requireUppercase = requireUppercase;
        this.requireLowercase = requireLowercase;
        this.requireDigit = requireDigit;
        this.requireSpecial = requireSpecial;
    }

    public List<User> getAllUsers() {
        return userRepository.findAllByOrderByDisplayNameAsc();
    }

    public List<User> getActiveUsers() {
        return userRepository.findAllByActiveTrueOrderByDisplayNameAsc();
    }

    public Optional<User> getUserById(String id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findFirstByUsernameOrderByIdAsc(username);
    }

    public User createUser(User user, String createdBy) {
        if (userRepository.findFirstByUsernameOrderByIdAsc(user.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        
        // Validate and hash password
        PasswordEncoderService.PasswordValidationResult validation = 
            passwordEncoderService.validatePassword(
                user.getPassword(),
                minPasswordLength,
                requireUppercase,
                requireLowercase,
                requireDigit,
                requireSpecial
            );
        
        if (!validation.isValid()) {
            throw new IllegalArgumentException(validation.getErrorMessage());
        }
        
        // Hash password before storing
        String hashedPassword = passwordEncoderService.encode(user.getPassword());
        user.setPassword(hashedPassword);
        
        // If creating a new admin, demote existing admin
        if (user.isAdmin()) {
            demoteExistingAdmin(createdBy);
        }
        
        user.setCreatedBy(createdBy);
        user.setUpdatedBy(createdBy);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        
        User saved = userRepository.save(user);
        securityAuditService.logUserCreated(saved.getId(), createdBy, saved.getUsername());
        
        return saved;
    }

    public User updateUser(String id, User updatedUser, String updatedBy) {
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        // If promoting to admin, demote existing admin (unless this user is already admin)
        if (updatedUser.isAdmin() && !existingUser.isAdmin()) {
            demoteExistingAdmin(updatedBy);
        }
        
        // Don't allow changing username
        updatedUser.setUsername(existingUser.getUsername());
        updatedUser.setId(id);
        updatedUser.setUpdatedBy(updatedBy);
        updatedUser.setUpdatedAt(Instant.now());
        updatedUser.setCreatedAt(existingUser.getCreatedAt());
        updatedUser.setCreatedBy(existingUser.getCreatedBy());
        
        return userRepository.save(updatedUser);
    }

    public void deleteUser(String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        // Prevent deactivating the last admin
        if (user.isAdmin() && user.isActive()) {
            long activeAdminCount = userRepository.findByIsAdminTrue()
                    .stream()
                    .filter(User::isActive)
                    .count();
            if (activeAdminCount <= 1) {
                throw new IllegalStateException("Cannot deactivate the last admin user");
            }
        }
        
        user.setActive(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }
    
    /**
     * Demotes the existing admin user to regular user
     * Only one admin can exist at a time
     */
    private void demoteExistingAdmin(String updatedBy) {
        Instant now = Instant.now();
        for (User admin : userRepository.findByIsAdminTrue()) {
            if (!admin.isActive()) {
                continue;
            }
            admin.setAdmin(false);
            admin.setUpdatedBy(updatedBy);
            admin.setUpdatedAt(now);
            userRepository.save(admin);
        }
    }
    
    /**
     * Gets the current admin user
     */
    public Optional<User> getCurrentAdmin() {
        return userRepository.findFirstByIsAdminTrueAndActiveTrueOrderByIdAsc();
    }

    public void hardDeleteUser(String id) {
        userRepository.deleteById(id);
    }

    public boolean validateUser(String username, String password) {
        Optional<User> userOpt = userRepository.findFirstByUsernameAndActiveTrueOrderByIdAsc(username);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        
        // Check if password is hashed (starts with $2a$, $2b$, or $2y$ for BCrypt)
        String storedPassword = user.getPassword();
        if (storedPassword != null && (storedPassword.startsWith("$2a$") || 
                                       storedPassword.startsWith("$2b$") || 
                                       storedPassword.startsWith("$2y$"))) {
            // Password is hashed, use password encoder
            return passwordEncoderService.matches(password, storedPassword);
        } else {
            // Legacy plain text password - migrate on successful login
            boolean matches = storedPassword != null && storedPassword.equals(password);
            if (matches) {
                // Migrate to hashed password
                String hashedPassword = passwordEncoderService.encode(password);
                user.setPassword(hashedPassword);
                user.setUpdatedAt(Instant.now());
                userRepository.save(user);
            }
            return matches;
        }
    }

    /**
     * Verifies the raw password matches the stored credential for this user (BCrypt or legacy plain text).
     */
    public boolean verifyCurrentPassword(String userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        String storedPassword = user.getPassword();
        if (storedPassword != null
                && (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$"))) {
            return passwordEncoderService.matches(rawPassword, storedPassword);
        }
        return storedPassword != null && storedPassword.equals(rawPassword);
    }
    
    /**
     * Update user password with validation and hashing.
     */
    public void updatePassword(String userId, String newPassword, String updatedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        // Validate password
        PasswordEncoderService.PasswordValidationResult validation = 
            passwordEncoderService.validatePassword(
                newPassword,
                minPasswordLength,
                requireUppercase,
                requireLowercase,
                requireDigit,
                requireSpecial
            );
        
        if (!validation.isValid()) {
            throw new IllegalArgumentException(validation.getErrorMessage());
        }
        
        // Hash and update password
        String hashedPassword = passwordEncoderService.encode(newPassword);
        user.setPassword(hashedPassword);
        user.setUpdatedBy(updatedBy);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        
        securityAuditService.logPasswordChange(userId, updatedBy);
    }
}

