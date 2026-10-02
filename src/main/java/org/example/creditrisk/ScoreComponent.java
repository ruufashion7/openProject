package org.example.creditrisk;

public record ScoreComponent(
        String parameter,
        String rawValue,
        double scoreObtained,
        double maximumScore,
        String reason
) {
}
