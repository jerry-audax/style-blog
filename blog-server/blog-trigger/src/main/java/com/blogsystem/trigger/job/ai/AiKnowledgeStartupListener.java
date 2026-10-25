package com.blogsystem.trigger.job.ai;

import com.blogsystem.application.ai.service.AiApplicationFacade;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

/** A startup trigger, not an index rebuild. Registered only by enabled in-process bootstrap. */
public class AiKnowledgeStartupListener {
    private final AiApplicationFacade ai;

    public AiKnowledgeStartupListener(AiApplicationFacade ai) {
        this.ai = ai;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ai.initializeKnowledgeOnStartup();
    }
}
