package com.blogsystem.trigger.http.surprise;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;

import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(
        assignableTypes = {SurprisePublicController.class, SurpriseAdminController.class})
public class SurpriseExceptionHandler {
    @ExceptionHandler(SurpriseCatalogException.class)
    public ResponseEntity<ApiResponse<Void>> catalog(SurpriseCatalogException e) {
        return fail(
                switch (e.reason()) {
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case MISSING_VERSION -> 428;
                    case INVALID_REQUEST -> 422;
                    case UNAVAILABLE, OPERATION_NOT_READY -> 503;
                },
                e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson() {
        return fail(422, "视频资料格式不正确");
    }

    @ExceptionHandler(OwnerAccessException.class)
    public ResponseEntity<ApiResponse<Void>> owner(OwnerAccessException e) {
        return fail(
                switch (e.reason()) {
                    case OWNER_ONLY -> 403;
                    case INVALID_CREDENTIALS, PASSWORD_REQUIRED -> 401;
                    case RATE_LIMITED -> 429;
                    case UNAVAILABLE -> 503;
                },
                e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> login() {
        return fail(401, "请先登录管理端");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> role() {
        return fail(403, "仅博主可管理惊喜视频");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException e) {
        return fail(422, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(IllegalStateException e) {
        return fail(503, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        return fail(500, "惊喜视频操作失败，请稍后重试");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status, message));
    }
}
