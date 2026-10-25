package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;

import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(-3)
@RestControllerAdvice(
        assignableTypes = {MediaDeletionController.class, MediaCatalogCapabilitiesController.class})
public final class MediaDeletionExceptionHandler {
    @ExceptionHandler(MediaDeletionException.class)
    public ResponseEntity<ApiResponse<Void>> domain(MediaDeletionException error) {
        return fail(
                switch (error.reason()) {
                    case MISSING_VERSION -> 428;
                    case INVALID_REQUEST -> 422;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case UNAVAILABLE, OPERATION_NOT_READY -> 503;
                },
                error.getMessage());
    }

    @ExceptionHandler(OwnerAccessException.class)
    public ResponseEntity<ApiResponse<Void>> owner(OwnerAccessException error) {
        return fail(
                switch (error.reason()) {
                    case PASSWORD_REQUIRED, INVALID_CREDENTIALS -> 401;
                    case OWNER_ONLY -> 403;
                    case RATE_LIMITED -> 429;
                    case UNAVAILABLE -> 503;
                },
                "仅博主有效密码会话可管理删除操作");
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> login() {
        return fail(401, "请先登录管理端");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> role() {
        return fail(403, "仅博主可管理删除操作");
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid() {
        return fail(422, "删除参数格式不正确，请重新预览");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return fail(503, "删除状态暂不可用，请保留原操作编号");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.fail(status, message));
    }
}
