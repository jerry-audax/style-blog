package com.blogsystem.ai.interfaces.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/ai/conversations")
public class AiConversationController {
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> clear(@PathVariable String id) {
        return ResponseEntity.noContent().build();
    }
}
