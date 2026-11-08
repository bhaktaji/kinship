package com.kinship.user.dto;

import java.math.BigDecimal;

public record UserInterestResponse(int interestId, String slug, String name, BigDecimal weight) {
}
