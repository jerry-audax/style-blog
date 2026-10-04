package com.blogsystem.asset.interfaces.web;

import com.blogsystem.asset.domain.model.ImageStorageException;
import com.blogsystem.identity.domain.model.OwnerAccessException;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@Order(-1)
@RestControllerAdvice(assignableTypes = ImageController.class)
public class ImageExceptionHandler {
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

    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiResponse<Void>> storage(ImageStorageException error) {
        int status = switch (error.reason()) {
            case NOT_CONFIGURED -> 503;
            case UNKNOWN_OUTCOME -> 504;
            default -> 502;
        };
        return fail(status, error.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> login() {
        return fail(401, "请先登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> role() {
        return fail(403, "仅管理员可管理图片");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge() {
        return fail(413, "图片超过大小限制");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, MultipartException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> invalid() {
        return fail(400, "图片或请求参数无效（JPEG/PNG/GIF/WebP；头像 2 MiB，其他 10 MiB）");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        return fail(500, "图片操作失败，请联系管理员");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status, message));
    }
}
