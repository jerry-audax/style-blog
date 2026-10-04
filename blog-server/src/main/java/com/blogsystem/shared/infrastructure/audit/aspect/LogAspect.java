package com.blogsystem.shared.infrastructure.audit.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.shared.application.annotation.OpLog;
import com.blogsystem.shared.infrastructure.audit.entity.OperationLog;
import com.blogsystem.shared.infrastructure.audit.mapper.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Aspect
@Component
@RequiredArgsConstructor
public class LogAspect {

    private final OperationLogMapper operationLogMapper;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.blogsystem.shared.application.annotation.OpLog)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        MethodSignature signature = (MethodSignature) point.getSignature();
        OpLog annotation = signature.getMethod().getAnnotation(OpLog.class);

        OperationLog log = new OperationLog();
        log.setModule(annotation.module());
        log.setAction(annotation.action());
        log.setContent(annotation.module() + "-" + annotation.action());
        log.setIp(request.getRemoteAddr());
        log.setUserAgent(request.getHeader("User-Agent"));
        log.setCreatedAt(LocalDateTime.now());

        try {
            log.setUserId(StpUtil.getLoginIdAsLong());
        } catch (Exception ignored) {
        }

        try {
            String args = objectMapper.writeValueAsString(point.getArgs());
            // 敏感字段脱敏：密码、手机号
            args = args.replaceAll("\"(oldPassword|newPassword|password)\":\"[^\"]*\"",
                    "\"$1\":\"****\"");
            args = args.replaceAll("\"phone\":\"(\\d{3})\\d*(\\d{4})\"",
                    "\"phone\":\"$1****$2\"");
            if (args.length() > 500) args = args.substring(0, 500);
            log.setRequestData(args);
        } catch (Exception ignored) {
        }

        // Try to proceed; record failure log on exception then re-throw
        Object result;
        try {
            result = point.proceed();
            // Success
            log.setContent(annotation.module() + "-" + annotation.action());
            try {
                operationLogMapper.insert(log);
            } catch (Exception ignored) {
            }
        } catch (Throwable e) {
            // Record failure log before re-throwing
            String errorMsg = e.getMessage();
            if (errorMsg == null) errorMsg = "(无错误信息)";
            if (errorMsg.length() > 200) errorMsg = errorMsg.substring(0, 200);
            String content = "[FAIL] " + annotation.module() + "-" + annotation.action()
                    + ": " + e.getClass().getSimpleName() + " - " + errorMsg;
            if (content.length() > 500) content = content.substring(0, 500);
            log.setContent(content);
            try {
                operationLogMapper.insert(log);
            } catch (Exception ignored) {
            }
            throw e;
        }

        return result;
    }
}
