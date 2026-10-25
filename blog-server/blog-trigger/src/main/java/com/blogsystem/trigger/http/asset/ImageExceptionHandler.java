package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;

import com.blogsystem.domain.asset.image.model.valobj.ImageStorageException;
import com.blogsystem.domain.identity.model.OwnerAccessException;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(-1)
@RestControllerAdvice(assignableTypes = {ImageController.class, ImageCatalogController.class})
public class ImageExceptionHandler {
    @ExceptionHandler(com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException.class)
    public ResponseEntity<ApiResponse<Void>> metadataUnavailable() {
        return fail(503, "图片元数据暂不可用，请稍后重试");
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

    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiResponse<Void>> storage(ImageStorageException error) {
        int status =
                switch (error.reason()) {
                    case NOT_CONFIGURED, OPERATION_NOT_READY -> 503;
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

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MultipartException.class,
        MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class,
        HttpMediaTypeNotSupportedException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid() {
        return fail(400, "图片或请求参数无效（JPEG/PNG/GIF/WebP；头像 2 MiB，其他 10 MiB）");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        return fail(500, "图片操作失败，请联系管理员");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) {
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiResponse.fail(status, message));
    }
}
