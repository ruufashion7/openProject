/**
 * Service for input sanitization and XSS prevention.
 * Provides utilities to sanitize user input and prevent XSS attacks.
 */

export class SanitizationService {
  /**
   * Sanitize a string by removing potentially dangerous characters.
   * @param input Input string to sanitize
   * @returns Sanitized string
   */
  static sanitizeInput(input: string | null | undefined): string {
    if (!input) {
      return '';
    }

    // Remove HTML tags
    let sanitized = input.replace(/<[^>]*>/g, '');
    
    // Remove script tags and event handlers
    sanitized = sanitized.replace(/javascript:/gi, '');
    sanitized = sanitized.replace(/on\w+\s*=/gi, '');
    
    // Remove null bytes
    sanitized = sanitized.replace(/\0/g, '');
    
    // Trim whitespace
    sanitized = sanitized.trim();
    
    return sanitized;
  }

  /**
   * Sanitize a filename to prevent path traversal attacks.
   * @param filename Filename to sanitize
   * @returns Sanitized filename
   */
  static sanitizeFilename(filename: string | null | undefined): string {
    if (!filename) {
      return '';
    }

    // Remove path traversal attempts
    let sanitized = filename.replace(/\.\./g, '');
    sanitized = sanitized.replace(/[\/\\]/g, '');
    
    // Remove control characters
    sanitized = sanitized.replace(/[\x00-\x1F\x7F]/g, '');
    
    // Remove leading/trailing dots and spaces (Windows)
    sanitized = sanitized.replace(/^[\s.]+|[\s.]+$/g, '');
    
    return sanitized;
  }

  /**
   * Validate and sanitize an email address.
   * @param email Email to validate
   * @returns Sanitized email or empty string if invalid
   */
  static sanitizeEmail(email: string | null | undefined): string {
    if (!email) {
      return '';
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    const sanitized = email.trim().toLowerCase();
    
    if (emailRegex.test(sanitized)) {
      return sanitized;
    }
    
    return '';
  }

  /**
   * Escape HTML special characters.
   * Angular's interpolation already does this, but useful for manual HTML building.
   * @param input Input string
   * @returns Escaped string
   */
  static escapeHtml(input: string | null | undefined): string {
    if (!input) {
      return '';
    }

    const map: { [key: string]: string } = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#039;'
    };

    return input.replace(/[&<>"']/g, (m) => map[m]);
  }

  /**
   * Validate input length.
   * @param input Input string
   * @param maxLength Maximum allowed length
   * @returns Truncated string if exceeds maxLength
   */
  static validateLength(input: string | null | undefined, maxLength: number): string {
    if (!input) {
      return '';
    }

    if (input.length > maxLength) {
      return input.substring(0, maxLength);
    }

    return input;
  }

  /**
   * Remove SQL injection patterns (defense in depth).
   * @param input Input string
   * @returns Sanitized string
   */
  static sanitizeSqlInput(input: string | null | undefined): string {
    if (!input) {
      return '';
    }

    // Remove SQL comment patterns
    let sanitized = input.replace(/--/g, '');
    sanitized = sanitized.replace(/\/\*/g, '');
    sanitized = sanitized.replace(/\*\//g, '');
    
    // Remove common SQL keywords in suspicious contexts
    // Note: This is basic protection, parameterized queries are still required
    
    return sanitized;
  }
}

