package com.blogsystem.trigger.http.content;

import com.blogsystem.domain.content.model.SitePublicationUnavailable;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SitePublicationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SitePublicationExceptionHandler {
    @ExceptionHandler(SitePublicationUnavailable.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(SitePublicationUnavailable exception) {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, exception.getMessage()));
    }
}
