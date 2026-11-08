package com.kinship.user.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record InterestSelection(
        @Positive(message = "interest_id must be positive") int interestId,
        @DecimalMin(value = "0.1", message = "weight must be >= 0.1")
        @DecimalMax(value = "5.0", message = "weight must be <= 5.0")
        BigDecimal weight) {
}
