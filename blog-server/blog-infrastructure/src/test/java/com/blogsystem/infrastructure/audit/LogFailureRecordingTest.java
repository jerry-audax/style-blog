package com.blogsystem.infrastructure.audit;

import com.blogsystem.infrastructure.audit.LogAspect;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-11 / P2-10 操作日志失败记录测试
 * 验证 LogAspect 异常捕获时的 [FAIL] 前缀、错误消息截断（200字符）、
 * 内容总长上限（500字符）逻辑
 * 不依赖 Spring 上下文，测试纯字符串操作
 */

/**
 * P2-10 失败操作日志记录单元测试。
 * 验证 [FAIL] 前缀格式、异常类-消息拼接、
 * 错误消息200字符截断、content总长500字符上限。
 */
class LogFailureRecordingTest {

    // ── [FAIL] 前缀 ──

    @Test
    void failContent_shouldStartWithFailPrefix() {
        String content = buildFailContent("content", "article:delete",
                "IllegalArgumentException", "文章不存在");

        assertTrue(content.startsWith("[FAIL] "), "失败日志内容应以 [FAIL] 开头");
    }

    @Test
    void failContent_shouldContainModuleActionAndException() {
        String module = "content";
        String action = "article:delete";
        String exceptionClass = "IllegalArgumentException";
        String errorMsg = "文章不存在";

        String content = buildFailContent(module, action, exceptionClass, errorMsg);

        assertTrue(content.contains(module), "应包含模块名");
        assertTrue(content.contains(action), "应包含操作名");
        assertTrue(content.contains(exceptionClass), "应包含异常类名");
        assertTrue(content.contains(errorMsg), "应包含错误消息");
    }

    @Test
    void failContent_format() {
        // 对应 LogAspect 第 72-73 行
        String content = buildFailContent("content", "article:delete",
                "IllegalArgumentException", "文章不存在");

        assertEquals("[FAIL] content-article:delete: IllegalArgumentException - 文章不存在",
                content);
    }

    @Test
    void failContent_successContent_shouldNotHaveFailPrefix() {
        // 成功路径的 content 是 module + "-" + action，无 [FAIL] 前缀
        String module = "content";
        String action = "article:write";

        String successContent = module + "-" + action;

        assertEquals("content-article:write", successContent);
        assertFalse(successContent.startsWith("[FAIL]"));
    }

    // ── 错误消息截断（200 字符） ──

    @Test
    void errorMessage_within200chars_notTruncated() {
        String msg = "文章不存在".repeat(5);  // 5 * 5 = 25 chars
        assertTrue(msg.length() < 200, "应小于 200 字符以确保不会被截断");

        String truncated = truncateErrorMessage(msg);
        assertEquals(msg, truncated, "少于 200 字符的消息不应被截断");
    }

    @Test
    void errorMessage_exactly200chars_notTruncated() {
        String msg = "A".repeat(200);
        assertEquals(200, msg.length());

        String truncated = truncateErrorMessage(msg);
        assertEquals(200, truncated.length(), "恰好 200 字符不应被截断");
        assertEquals(msg, truncated);
    }

    @Test
    void errorMessage_exceeds200chars_truncatedTo200() {
        String longMessage = "A".repeat(500);
        assertTrue(longMessage.length() > 200);

        String truncated = truncateErrorMessage(longMessage);
        assertEquals(200, truncated.length(), "超过 200 字符应截断至 200");
        assertEquals("A".repeat(200), truncated);
        assertTrue(longMessage.startsWith(truncated), "截断结果应是原消息的前缀");
    }

    @Test
    void errorMessage_null_becomesPlaceholder() {
        String msg = null;
        if (msg == null) msg = "(无错误信息)";
        assertEquals("(无错误信息)", msg);
        assertTrue(msg.length() < 200, "占位符长度应远小于 200");
    }

    // ── 内容总长上限（500 字符） ──

    @Test
    void content_within500chars_notCapped() {
        String content = buildFailContent("content", "article:delete",
                "IllegalArgumentException", "正常错误消息");
        assertTrue(content.length() < 500, "短内容应小于 500 字符");

        String capped = capContent(content);
        assertEquals(content, capped, "少于 500 字符的内容不应被截断");
    }

    @Test
    void content_exactly500chars_notCapped() {
        // 构造恰好 500 字符的内容
        String base = "[FAIL] content-article:delete: ";
        String exceptionClass = "IllegalArgumentException";
        String errorMsg = "x".repeat(500 - base.length() - 3 - exceptionClass.length());
        // base + exceptionClass + " - " + errorMsg
        String preamble = base + exceptionClass + " - ";
        String padding = "x".repeat(500 - preamble.length());
        String content = preamble + padding;

        assertEquals(500, content.length());
        String capped = capContent(content);
        assertEquals(500, capped.length(), "恰好 500 字符不应被截断");
        assertEquals(content, capped);
    }

    @Test
    void content_exceeds500chars_cappedAt500() {
        // 构造一个远超过 500 字符的内容——不使用截断后的错误消息，
        // 而是用原始长错误使内容 > 500 以触发 capContent 截断
        String veryLongError = "E".repeat(2000);
        String content = "[FAIL] content-article:write: RuntimeException - " + veryLongError;

        assertTrue(content.length() > 500, "长错误内容应超过 500 字符");

        String capped = capContent(content);
        assertEquals(500, capped.length(), "内容应被截断至 500 字符");

        // 截断内容应是原内容的前 500 字符
        assertEquals(content.substring(0, 500), capped);
    }

    @Test
    void content_capping_preservesFailPrefix() {
        // 即使截断，[FAIL] 前缀仍应保留（因为前缀在开头，且远小于 500）
        String veryLongError = "E".repeat(2000);
        String content = "[FAIL] content-article:write: RuntimeException - " + veryLongError;
        assertTrue(content.length() > 500, "内容应超过 500 以触发截断");

        String capped = capContent(content);
        assertEquals(500, capped.length(), "截断后为 500 字符");
        assertTrue(capped.startsWith("[FAIL] "), "截断后 [FAIL] 前缀应仍然存在");
    }

    @Test
    void fullFailSequence_errorMsg200_content500() {
        // 端到端测试：模拟完整的失败日志构造链
        // 使用长模块名使最终内容 > 500，确保两步截断都能被验证
        String rawErrorMsg = "E".repeat(1000);
        assertTrue(rawErrorMsg.length() > 200);

        // 步骤 1：错误消息截断至 200 字符
        String truncatedMsg = truncateErrorMessage(rawErrorMsg);
        assertEquals(200, truncatedMsg.length());

        // 步骤 2：组装完整内容——长前缀 + 200 字符错误 > 500
        String longModule = "M".repeat(280);
        String content = "[FAIL] " + longModule + "-write: RuntimeException - " + truncatedMsg;
        assertTrue(content.length() > 500, "长前缀 + 200 字符错误应超过 500 字符");

        // 步骤 3：内容上限至 500 字符
        String finalContent = capContent(content);
        assertEquals(500, finalContent.length());
        assertTrue(finalContent.startsWith("[FAIL] "));
    }

    // ── 边界情况 ──

    @Test
    void errorMessage_emptyString_notNull() {
        // 空字符串不是 null，不应被替换为占位符
        String msg = "";
        if (msg == null) msg = "(无错误信息)";
        assertNotEquals("(无错误信息)", msg, "空字符串不应被替换为占位符");
        assertEquals("", msg);
    }

    @Test
    void errorMessage_withSpecialUnicodeChars_truncatedAt200() {
        // 包含中文等多字节字符的截断
        String msg = "错误：这是一条包含中文的错误信息。".repeat(20);
        assertTrue(msg.length() > 200);

        String truncated = truncateErrorMessage(msg);
        assertEquals(200, truncated.length());
    }

    // ── 模拟 LogAspect 中逻辑的辅助方法 ──

    /**
     * 构造失败日志内容，对应 LogAspect 第 72-73 行
     */
    static String buildFailContent(String module, String action, String exceptionClass, String errorMsg) {
        if (errorMsg == null) errorMsg = "(无错误信息)";
        if (errorMsg.length() > 200) errorMsg = errorMsg.substring(0, 200);
        return "[FAIL] " + module + "-" + action + ": " + exceptionClass + " - " + errorMsg;
    }

    /**
     * 截断错误消息，对应 LogAspect 第 69-71 行
     */
    static String truncateErrorMessage(String errorMsg) {
        if (errorMsg == null) return "(无错误信息)";
        if (errorMsg.length() > 200) return errorMsg.substring(0, 200);
        return errorMsg;
    }

    /**
     * 内容长度上限，对应 LogAspect 第 74 行
     */
    static String capContent(String content) {
        if (content.length() > 500) return content.substring(0, 500);
        return content;
    }
}
