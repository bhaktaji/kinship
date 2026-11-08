package com.kinship.user.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public record LocationRequest(
        @DecimalMin(value = "-90.0", message = "lat must be >= -90")
        @DecimalMax(value = "90.0", message = "lat must be <= 90")
        double lat,
        @DecimalMin(value = "-180.0", message = "lng must be >= -180")
        @DecimalMax(value = "180.0", message = "lng must be <= 180")
        double lng) {
}
