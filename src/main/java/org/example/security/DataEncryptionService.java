package org.example.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting sensitive data.
 * Uses AES-GCM encryption for sensitive fields.
 * SECURITY: Sensitive data should be encrypted before storage or transmission.
 */
@Service
public class DataEncryptionService {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 16; // 128 bits
    private static final int KEY_SIZE = 256; // 256 bits
    
    private final SecretKey secretKey;
    
    public DataEncryptionService(@Value("${security.encryption.key:}") String encryptionKey) {
        if (encryptionKey != null && !encryptionKey.isBlank()) {
            // Use provided key (from environment variable)
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } else {
            // SECURITY WARNING: Generate a new key (should be set via environment variable in production)
            this.secretKey = generateSecretKey();
            System.err.println("WARNING: Using auto-generated encryption key. Set SECURITY_ENCRYPTION_KEY environment variable in production!");
        }
    }
    
    /**
     * Encrypt sensitive data.
     * @param plaintext Data to encrypt
     * @return Base64-encoded encrypted data with IV prepended
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
            
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Prepend IV to ciphertext
            byte[] encryptedWithIv = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, encryptedWithIv, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, encryptedWithIv, GCM_IV_LENGTH, ciphertext.length);
            
            return Base64.getEncoder().encodeToString(encryptedWithIv);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    /**
     * Decrypt sensitive data.
     * @param encryptedData Base64-encoded encrypted data with IV prepended
     * @return Decrypted plaintext
     */
    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isBlank()) {
            return encryptedData;
        }
        
        try {
            byte[] encryptedWithIv = Base64.getDecoder().decode(encryptedData);
            
            // Extract IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedWithIv, 0, iv, 0, GCM_IV_LENGTH);
            
            // Extract ciphertext
            byte[] ciphertext = new byte[encryptedWithIv.length - GCM_IV_LENGTH];
            System.arraycopy(encryptedWithIv, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
    
    /**
     * Generate a secret key (for initial setup only).
     * In production, use a key from secure key management.
     */
    private SecretKey generateSecretKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(KEY_SIZE);
            return keyGenerator.generateKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate secret key", e);
        }
    }
    
    /**
     * Get the base64-encoded secret key (for configuration).
     * SECURITY: This should only be used for initial setup, never expose in production.
     */
    public String getSecretKeyBase64() {
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }
}

