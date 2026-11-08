package com.kinship.auth.dto;

import com.kinship.user.dto.UserProfileResponse;

public record AuthResponse(UserProfileResponse user, String accessToken, String tokenType, long expiresIn) {
}
