package com.blogsystem.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
        @NotBlank String code,
        @NotBlank @Size(min = 6, max = 32) String newPassword
) {}
