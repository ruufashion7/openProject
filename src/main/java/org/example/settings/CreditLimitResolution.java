package org.example.settings;

/**
 * Resolved credit limit for a customer vs total outstanding.
 */
public record CreditLimitResolution(
        Double creditLimitOverride,
        Double effectiveCreditLimit,
        String creditLimitSource,
        boolean overCreditLimit,
        Double creditLimitUtilization
) {
}
