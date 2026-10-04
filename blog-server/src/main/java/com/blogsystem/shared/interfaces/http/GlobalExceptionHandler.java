package com.blogsystem.shared.interfaces.http;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.blogsystem.identity.domain.model.OwnerAccessException;
import com.blogsystem.shared.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OwnerAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleOwnerAccess(OwnerAccessException e) {
        int status = switch (e.reason()) {
            case INVALID_CREDENTIALS, PASSWORD_REQUIRED -> 401;
            case OWNER_ONLY -> 403;
            case RATE_LIMITED -> 429;
            case UNAVAILABLE -> 503;
        };
        return fail(status, e.getMessage());
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotRole(NotRoleException e) {
        return fail(403, "权限不足");
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException e) {
        return fail(401, "\u8bf7\u5148\u767b\u5f55");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotPermission(NotPermissionException e) {
        return fail(403, "\u6743\u9650\u4e0d\u8db3");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return fail(400, e.getMessage() == null ? "Bad Request" : e.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainValidation(DomainException e) {
        return fail(400, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() == null
                ? "\u53c2\u6570\u6821\u9a8c\u5931\u8d25"
                : e.getBindingResult().getFieldError().getDefaultMessage();
        return fail(400, msg);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoResource(Exception e) {
        return fail(404, "Not Found");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException e,
                                                                 HttpServletRequest request) {
        log.warn("Not acceptable request on {}: {}", request.getRequestURI(), e.getMessage());
        return fail(406, "Not Acceptable");
    }

    // Multipart parsing can fail before a controller is resolved, so scoped media advice cannot handle this case.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadLimit(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(ApiResponse.fail(413, "图片超过大小限制"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e, HttpServletRequest request) {
        String uri = request.getRequestURI();
        log.warn("Invalid multipart request rejected: {} {}", request.getMethod(), uri);
        return fail(400, "Bad Request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (isScannerPath(uri)) {
            return fail(404, "Not Found");
        }
        log.error("Unhandled error on {}", uri, e);
        return fail(500, "\u7cfb\u7edf\u7e41\u5fd9");
    }

    private ResponseEntity<ApiResponse<Void>> fail(int code, String message) {
        return ResponseEntity.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(code, message));
    }

    private boolean isScannerPath(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase();
        return u.contains(".cgi") || u.contains(".php") || u.contains(".asp")
                || u.contains("wp-admin") || u.contains("wp-login")
                || u.contains("phpmyadmin") || u.contains("adminer")
                || u.contains(".env") || u.contains("actuator")
                || u.contains("geoserver") || u.contains("solr")
                || u.contains("/vendor/") || u.contains("/.git/");
    }

}
