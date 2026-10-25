package com.blogsystem.trigger.http.identity.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordLoginRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone, @NotBlank String password) {}
