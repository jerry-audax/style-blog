package com.blogsystem.trigger.http.identity.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordUpdateRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}
