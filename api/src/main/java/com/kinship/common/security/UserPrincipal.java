package com.kinship.common.security;

import java.util.UUID;

public record UserPrincipal(UUID id, String email) {
}
