package com.blogsystem.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.blogsystem.infrastructure.mybatis.mapper.administration.OperationLogMapper;
import com.blogsystem.infrastructure.mybatis.record.administration.OperationLogRecord;
import com.blogsystem.types.audit.OpLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;

class LogAspectProxyTest {
    private final OperationLogMapper mapper = mock(OperationLogMapper.class);
    private final IllegalStateException failure = new IllegalStateException("fixture failure");

    private AuditedOperations proxy() {
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("audit-contract-fixture");
        var factory = new AspectJProxyFactory(new AuditedOperations(failure));
        factory.setProxyTargetClass(true);
        factory.addAspect(new LogAspect(mapper, request, new ObjectMapper()));
        AuditedOperations proxy = factory.getProxy();
        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isAopProxy(proxy));
        return proxy;
    }

    @Test void successThroughRealAspectWritesExactlyOneSanitizedAuditRow() {
        assertEquals("saved", proxy().save(Map.of("password", "fixture-password")));
        var row = ArgumentCaptor.forClass(OperationLogRecord.class);
        verify(mapper).insert(row.capture());
        assertEquals("content", row.getValue().getModule());
        assertEquals("save", row.getValue().getAction());
        assertEquals("content-save", row.getValue().getContent());
        assertEquals("127.0.0.1", row.getValue().getIp());
        assertEquals("audit-contract-fixture", row.getValue().getUserAgent());
        assertFalse(row.getValue().getRequestData().contains("fixture-password"));
        verifyNoMoreInteractions(mapper);
    }

    @Test void failureThroughRealAspectAuditsOnceAndPropagatesOriginalException() {
        var proxy = proxy();
        assertSame(failure, assertThrows(IllegalStateException.class, proxy::fail));
        var row = ArgumentCaptor.forClass(OperationLogRecord.class);
        verify(mapper).insert(row.capture());
        assertEquals("[FAIL] content-delete: IllegalStateException - fixture failure", row.getValue().getContent());
        verifyNoMoreInteractions(mapper);
    }

    public static class AuditedOperations {
        private final IllegalStateException failure;
        public AuditedOperations(IllegalStateException failure) { this.failure = failure; }
        @OpLog(module = "content", action = "save")
        public String save(Map<String, String> input) { return "saved"; }
        @OpLog(module = "content", action = "delete")
        public void fail() { throw failure; }
    }
}
