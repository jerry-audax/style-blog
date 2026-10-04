package com.blogsystem.music.interfaces.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.blogsystem.identity.domain.model.OwnerAccessException;
import com.blogsystem.music.domain.model.MusicException;
import com.blogsystem.music.domain.model.HostedMusicUnavailable;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes = {MusicAdminController.class, MusicPublicController.class, HostedMusicPublicController.class})
public class MusicExceptionHandler {
    @ExceptionHandler(HostedMusicUnavailable.class)
    public ResponseEntity<ApiResponse<Void>> hosted(HostedMusicUnavailable error) {
        return ResponseEntity.status(503).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResponse.fail(503, error.getMessage()));
    }

    @ExceptionHandler(MusicException.class)
    public ResponseEntity<ApiResponse<Void>> provider(MusicException error) {
        int status = switch (error.reason()) {
            case BUSY, NOT_AUTHORIZED -> 409;
            case NOT_CONFIGURED, UNAVAILABLE -> 503;
            case INVALID_REQUEST -> 422;
            case TRACK_NOT_FOUND -> 404;
            case PLAYLIST_NOT_FOUND, PLAYLIST_TOO_LARGE -> 422;
            default -> 502;
        };
        return fail(status, error.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> login() {
        return fail(401, "请先登录管理端");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> role() {
        return fail(403, "仅博主可管理音乐");
    }

    @ExceptionHandler(OwnerAccessException.class)
    public ResponseEntity<ApiResponse<Void>> owner(OwnerAccessException error) {
        int status = switch (error.reason()) {
            case INVALID_CREDENTIALS, PASSWORD_REQUIRED -> 401;
            case OWNER_ONLY -> 403;
            case RATE_LIMITED -> 429;
            case UNAVAILABLE -> 503;
        };
        return fail(status, error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        return fail(500, "音乐操作失败，请稍后检查状态");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status, message));
    }
}
