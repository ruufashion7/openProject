/**
 * Security service for frontend security utilities.
 * Provides input validation, sanitization, and security checks.
 */

import { SanitizationService } from './sanitization.service';

export class SecurityService {
  /**
   * Validate file before upload.
   */
  static validateFile(file: File | null | undefined): { valid: boolean; error?: string } {
    if (!file) {
      return { valid: false, error: 'File is required' };
    }

    // Aligned with backend SalesReceivableExcelUploadValidation / rate-list bulk upload (.xlsx only)
    const maxSize = 500 * 1024 * 1024;
    if (file.size > maxSize) {
      return { valid: false, error: `File size exceeds ${maxSize / (1024 * 1024)}MB limit` };
    }

    const fileName = file.name.toLowerCase();
    if (!fileName.endsWith('.xlsx')) {
      return { valid: false, error: 'Only Excel .xlsx files are allowed' };
    }

    // Validate filename
    const sanitizedFilename = SanitizationService.sanitizeFilename(file.name);
    if (sanitizedFilename !== file.name) {
      return { valid: false, error: 'Filename contains invalid characters' };
    }

    return { valid: true };
  }

  /**
   * Validate ID parameter.
   */
  static validateId(id: string | null | undefined): boolean {
    if (!id || id.length === 0 || id.length > 100) {
      return false;
    }
    // Allow alphanumeric, dash, underscore
    return /^[a-zA-Z0-9_-]+$/.test(id);
  }

  /**
   * Validate query parameter.
   */
  static validateQuery(query: string | null | undefined, minLength = 0, maxLength = 100): boolean {
    if (!query) {
      return minLength === 0;
    }
    const trimmed = query.trim();
    return trimmed.length >= minLength && trimmed.length <= maxLength;
  }

  /**
   * Sanitize query parameter.
   */
  static sanitizeQuery(query: string, maxLength = 100): string {
    return SanitizationService.sanitizeInput(query).substring(0, maxLength);
  }

  /**
   * Validate numeric parameter.
   */
  static validateNumber(value: number | null | undefined, min?: number, max?: number): boolean {
    if (value === null || value === undefined || isNaN(value)) {
      return false;
    }
    if (min !== undefined && value < min) {
      return false;
    }
    if (max !== undefined && value > max) {
      return false;
    }
    return true;
  }

  /**
   * Validate limit parameter for pagination.
   */
  static validateLimit(limit: number, defaultLimit = 20, maxLimit = 100): number {
    if (!this.validateNumber(limit, 1, maxLimit)) {
      return defaultLimit;
    }
    return limit;
  }

  /**
   * Validate page parameter for pagination.
   */
  static validatePage(page: number, defaultPage = 0): number {
    if (!this.validateNumber(page, 0)) {
      return defaultPage;
    }
    return page;
  }

  /**
   * Escape HTML to prevent XSS (for manual HTML building).
   */
  static escapeHtml(text: string): string {
    return SanitizationService.escapeHtml(text);
  }

  /**
   * Validate URL parameter.
   */
  static validateUrlParam(param: string | null | undefined, maxLength = 200): boolean {
    if (!param) {
      return false;
    }
    if (param.length > maxLength) {
      return false;
    }
    // Check for dangerous characters
    if (param.includes('<') || param.includes('>') || param.includes('"') || param.includes("'")) {
      return false;
    }
    return true;
  }
}

