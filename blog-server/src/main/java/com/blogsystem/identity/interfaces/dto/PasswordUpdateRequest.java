package com.blogsystem.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordUpdateRequest(
        @NotBlank String oldPassword,
        @NotBlank String newPassword
) {
}
