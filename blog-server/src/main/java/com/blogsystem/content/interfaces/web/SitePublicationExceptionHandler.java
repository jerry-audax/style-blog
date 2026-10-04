package com.blogsystem.content.interfaces.web;

import com.blogsystem.content.domain.model.SitePublicationUnavailable;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = SitePublicationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SitePublicationExceptionHandler {
    @ExceptionHandler(SitePublicationUnavailable.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(SitePublicationUnavailable exception) {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, exception.getMessage()));
    }
}
