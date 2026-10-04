package com.blogsystem.ai.interfaces.web;

import com.blogsystem.ai.application.conversation.ChatCommand;
import com.blogsystem.ai.application.conversation.ChatHandler;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/internal/v1/ai")
public class AiChatController {
    private final ChatHandler chatHandler;

    public AiChatController(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody Map<String, String> body) {
        return chatHandler.stream(new ChatCommand(body.get("principalId"), body.get("conversationId"), body.get("message")))
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }
}
