package com.blogsystem.surprise.interfaces.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes = {SurprisePublicController.class, SurpriseAdminController.class})
public class SurpriseExceptionHandler {
    @ExceptionHandler(NotLoginException.class) public ResponseEntity<ApiResponse<Void>> login() { return fail(401, "请先登录管理端"); }
    @ExceptionHandler(NotRoleException.class) public ResponseEntity<ApiResponse<Void>> role() { return fail(403, "仅博主可管理惊喜视频"); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException e) { return fail(422, e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<ApiResponse<Void>> unavailable(IllegalStateException e) { return fail(503, e.getMessage()); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Void>> unexpected() { return fail(500, "惊喜视频操作失败，请稍后重试"); }
    private ResponseEntity<ApiResponse<Void>> fail(int status, String message) { return ResponseEntity.status(status).body(ApiResponse.fail(status, message)); }
}
