package com.blogsystem.ai.application.conversation;

import reactor.core.publisher.Flux;

public interface ChatHandler {
    Flux<String> stream(ChatCommand command);
}
