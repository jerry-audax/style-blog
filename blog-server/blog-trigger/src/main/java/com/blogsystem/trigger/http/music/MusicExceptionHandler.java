package com.blogsystem.trigger.http.music;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;

import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.domain.music.model.HostedMusicUnavailable;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(
        assignableTypes = {
            MusicAdminController.class,
            MusicCoverBackfillController.class,
            MusicPublicController.class,
            HostedMusicPublicController.class
        })
public class MusicExceptionHandler {
    @ExceptionHandler(MusicCatalogException.class)
    public ResponseEntity<ApiResponse<Void>> catalog(MusicCatalogException error) {
        return fail(
                switch (error.reason()) {
                    case NOT_FOUND -> 404;
                    case CONFLICT, DUPLICATE -> 409;
                    case MISSING_VERSION -> 428;
                    case INVALID_REQUEST, TOO_LARGE -> 422;
                    case UNAVAILABLE, OPERATION_NOT_READY -> 503;
                },
                error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson() {
        return fail(422, "歌曲资料格式不正确");
    }

    @ExceptionHandler(HostedMusicUnavailable.class)
    public ResponseEntity<ApiResponse<Void>> hosted(HostedMusicUnavailable error) {
        return ResponseEntity.status(503)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResponse.fail(503, error.getMessage()));
    }

    @ExceptionHandler(MusicException.class)
    public ResponseEntity<ApiResponse<Void>> provider(MusicException error) {
        int status =
                switch (error.reason()) {
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
        int status =
                switch (error.reason()) {
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
