package com.blogsystem.ai.interfaces.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.blogsystem.ai.application.knowledge.RebuildIndexHandler;

import java.util.Map;

@RestController
@RequestMapping("/internal/v1/knowledge/indexes")
public class KnowledgeIndexController {
    private final RebuildIndexHandler handler;

    public KnowledgeIndexController(RebuildIndexHandler handler) {
        this.handler = handler;
    }

    @PostMapping(":rebuild")
    public ResponseEntity<Map<String, Object>> rebuild() {
        var build = handler.start();
        return ResponseEntity.accepted().body(Map.of("id", build.id(), "status", build.status().name()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> status(@org.springframework.web.bind.annotation.PathVariable String id) {
        return handler.find(id)
                .map(build -> {
                    Map<String, Object> body = Map.of("id", build.id(), "status", build.status().name(), "chunks", build.chunkCount());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
