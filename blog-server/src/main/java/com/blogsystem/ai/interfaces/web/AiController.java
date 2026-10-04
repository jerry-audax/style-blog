package com.blogsystem.ai.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.ai.application.AiApplicationFacade;
import com.blogsystem.ai.application.EnabledAi;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@EnabledAi
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiApplicationFacade aiService;

    @SaCheckLogin
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody Map<String, String> body) {
        String principalId = StpUtil.getLoginIdAsString();
        return aiService.chat(principalId, body.get("message"), body.get("sessionId"))
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @SaCheckLogin
    @DeleteMapping("/memory/{sessionId}")
    public ApiResponse<Void> clearMemory(@PathVariable String sessionId) {
        aiService.clearMemory(sessionId);
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
