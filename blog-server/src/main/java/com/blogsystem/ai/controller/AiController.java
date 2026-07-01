package com.blogsystem.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.blogsystem.ai.service.AiService;
import com.blogsystem.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final ChatMemory chatMemory;

    @SaCheckLogin
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody Map<String, String> body) {
        return aiService.chat(body.get("message"), body.get("sessionId"))
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @SaCheckLogin
    @DeleteMapping("/memory/{sessionId}")
    public ApiResponse<Void> clearMemory(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
        return ApiResponse.ok();
    }

    /**
     * 重建向量存储（管理端）。
     * 清空现有向量索引并重新加载所有已发布文章。
     */
    @SaCheckPermission("admin:user:list")
    @PostMapping("/rebuild-vectors")
    public ApiResponse<Map<String, Object>> rebuildVectors() {
        int count = aiService.rebuildVectors();
        if (count == -1) {
            return ApiResponse.ok(Map.of(
                    "success", false,
                    "message", "向量重建已在进行中，请稍后再试"
            ));
        }
        return ApiResponse.ok(Map.of(
                "success", true,
                "chunks", count,
                "message", "向量存储重建完成，共加载 " + count + " 个文档块"
        ));
    }
}
