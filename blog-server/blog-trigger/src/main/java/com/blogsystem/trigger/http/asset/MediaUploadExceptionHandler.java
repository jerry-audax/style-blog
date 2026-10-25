package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@Order(-2)
@RestControllerAdvice(assignableTypes = {MediaUploadController.class, com.blogsystem.trigger.http.music.MusicCoverBackfillController.class})
public final class MediaUploadExceptionHandler {
    @ExceptionHandler(com.blogsystem.domain.music.model.MusicCatalogException.class)
    public ResponseEntity<ApiResponse<Void>> musicCatalog(com.blogsystem.domain.music.model.MusicCatalogException error) {
        return fail(switch (error.reason()) {
            case NOT_FOUND -> 404;
            case CONFLICT, DUPLICATE -> 409;
            case MISSING_VERSION -> 428;
            case INVALID_REQUEST, TOO_LARGE -> 422;
            case UNAVAILABLE, OPERATION_NOT_READY -> 503;
        }, error.getMessage());
    }
    @ExceptionHandler(MediaUploadException.class)
    public ResponseEntity<ApiResponse<Void>> domain(MediaUploadException error) {
        return fail(
                switch (error.reason()) {
                    case INVALID_REQUEST -> 422;
                    case TOO_LARGE -> 413;
                    case MISSING_VERSION -> 428;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case UNAVAILABLE -> 503;
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
                "仅博主有效密码会话可访问上传操作");
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> login() {
        return fail(401, "请先登录管理端");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> role() {
        return fail(403, "仅博主可管理媒体上传");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> large() {
        return fail(413, "文件超过上传大小上限");
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MultipartException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid() {
        return fail(422, "上传参数或内容格式不正确");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return fail(503, "上传状态暂不可用，请保留原操作编号");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.fail(status, message));
    }
}
