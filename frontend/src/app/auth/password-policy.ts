/**
 * Mirrors {@code PasswordEncoderService} rules and defaults from {@code application.properties}.
 * Keep in sync with server validation.
 */

export interface PasswordPolicy {
  minLength: number;
  requireUppercase: boolean;
  requireLowercase: boolean;
  requireDigit: boolean;
  requireSpecial: boolean;
}

export const DEFAULT_PASSWORD_POLICY: PasswordPolicy = {
  minLength: 8,
  requireUppercase: true,
  requireLowercase: true,
  requireDigit: true,
  requireSpecial: true
};

const UPPERCASE = /.*[A-Z].*/;
const LOWERCASE = /.*[a-z].*/;
const DIGIT = /.*\d.*/;
/** Same character class as {@link PasswordEncoderService#SPECIAL_PATTERN}. */
const SPECIAL = /.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?].*/;

const COMMON_FRAGMENTS = [
  'password',
  '123456',
  '12345678',
  '1234',
  'qwerty',
  'abc123',
  'password1',
  'admin',
  'letmein',
  'welcome',
  'monkey',
  '1234567'
];

export interface PasswordRuleCheck {
  id: string;
  label: string;
  met: boolean;
}

export function isCommonPassword(password: string): boolean {
  const lower = password.toLowerCase();
  return COMMON_FRAGMENTS.some((c) => lower.includes(c));
}

export function evaluatePasswordRules(password: string, policy: PasswordPolicy): PasswordRuleCheck[] {
  const rules: PasswordRuleCheck[] = [
    {
      id: 'length',
      label: `At least ${policy.minLength} characters`,
      met: password.length >= policy.minLength
    }
  ];
  if (policy.requireUppercase) {
    rules.push({
      id: 'upper',
      label: 'At least one uppercase letter',
      met: UPPERCASE.test(password)
    });
  }
  if (policy.requireLowercase) {
    rules.push({
      id: 'lower',
      label: 'At least one lowercase letter',
      met: LOWERCASE.test(password)
    });
  }
  if (policy.requireDigit) {
    rules.push({
      id: 'digit',
      label: 'At least one digit',
      met: DIGIT.test(password)
    });
  }
  if (policy.requireSpecial) {
    rules.push({
      id: 'special',
      label: 'At least one special character (!@#$…)',
      met: SPECIAL.test(password)
    });
  }
  rules.push({
    id: 'common',
    label: 'Not a common or easily guessed password',
    met: !isCommonPassword(password)
  });
  return rules;
}

export function passwordMeetsPolicy(password: string, policy: PasswordPolicy): boolean {
  return evaluatePasswordRules(password, policy).every((r) => r.met);
}
