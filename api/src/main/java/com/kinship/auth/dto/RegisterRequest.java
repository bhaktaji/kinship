package com.kinship.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 254, message = "email is too long")
        String email,
        @NotBlank(message = "password is required")
        @Size(min = 10, max = 72, message = "password must be 10..72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "password must contain a letter and a digit")
        String password,
        @NotBlank(message = "display_name is required")
        @Size(min = 2, max = 50, message = "display_name must be 2..50 characters")
        String displayName,
        String timezone) {
}
