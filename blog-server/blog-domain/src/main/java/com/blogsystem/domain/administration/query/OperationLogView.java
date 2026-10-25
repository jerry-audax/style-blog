package com.blogsystem.domain.administration.query;

import java.time.LocalDateTime;

public record OperationLogView(
        Long id,
        Long userId,
        String module,
        String action,
        String content,
        String requestData,
        String ip,
        String userAgent,
        LocalDateTime createdAt) {}
