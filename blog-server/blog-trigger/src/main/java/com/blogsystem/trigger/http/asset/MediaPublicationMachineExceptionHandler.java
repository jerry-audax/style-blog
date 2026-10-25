package com.blogsystem.trigger.http.asset;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.trigger.http.support.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MediaPublicationMachineController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MediaPublicationMachineExceptionHandler {
    @ExceptionHandler(MediaPublicationPinException.class)
    public ResponseEntity<ApiResponse<Void>> domain(MediaPublicationPinException failure) {
        int status = switch (failure.reason()) { case INVALID_REQUEST -> 400; case LIMIT_EXCEEDED -> 413; case CONFLICT -> 409; case NOT_FOUND -> 404; case UNAVAILABLE -> 503; };
        return response(status, failure.getMessage());
    }
    @ExceptionHandler(MediaPublicationRequestException.class)
    public ResponseEntity<ApiResponse<Void>> request(MediaPublicationRequestException failure) { return response(failure.status(), failure.getMessage()); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception failure) { return response(503, "发布保护暂不可用"); }
    private static ResponseEntity<ApiResponse<Void>> response(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(ApiResponse.fail(status, message));
    }
}
