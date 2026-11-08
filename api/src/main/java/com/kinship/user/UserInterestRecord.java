package com.kinship.user;

import java.math.BigDecimal;

public record UserInterestRecord(int interestId, String slug, String name, BigDecimal weight) {
}
