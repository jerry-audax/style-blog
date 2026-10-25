package com.blogsystem.trigger.http.identity;

import cn.dev33.satoken.annotation.SaCheckLogin;

import com.blogsystem.application.identity.command.PasswordLoginCommand;
import com.blogsystem.application.identity.result.OwnerSessionView;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.trigger.http.identity.request.PasswordLoginRequest;
import com.blogsystem.trigger.http.identity.request.PasswordUpdateRequest;
import com.blogsystem.trigger.http.identity.request.ProfileUpdateRequest;
import com.blogsystem.trigger.http.support.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only the blog owner's password session remains; no public registration or SMS recovery.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final IdentityApplicationFacade authService;

    @PostMapping("/login/password")
    public ApiResponse<OwnerSessionView> loginByPassword(
            @RequestBody @Valid PasswordLoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(
                authService.loginByPassword(
                        new PasswordLoginCommand(request.phone(), request.password()),
                        http.getRemoteAddr(),
                        http.getHeader("User-Agent")));
    }

    @SaCheckLogin
    @GetMapping("/me")
    public ApiResponse<OwnerSessionView> me() {
        return ApiResponse.ok(authService.currentUser());
    }

    @SaCheckLogin
    @PutMapping("/profile")
    public ApiResponse<OwnerSessionView> updateProfile(
            @RequestBody @Valid ProfileUpdateRequest request) {
        return ApiResponse.ok(
                authService.updateProfile(request.nickname(), request.email(), request.avatar()));
    }

    @SaCheckLogin
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid PasswordUpdateRequest request) {
        authService.changePassword(request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    @SaCheckLogin
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }
}
