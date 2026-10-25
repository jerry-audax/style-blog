package com.blogsystem.application.ai;

import com.blogsystem.application.ai.service.AiApplicationFacade;
import com.blogsystem.domain.ai.gateway.AiChatPort;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiFlowContractTest {
    @Test void aiCapabilityExposesJdkFlowWithoutReactor() throws Exception {
        assertEquals(Flow.Publisher.class,
                AiChatPort.class.getMethod("stream", String.class, String.class, String.class).getReturnType());
        assertEquals(Flow.Publisher.class,
                AiApplicationFacade.class.getMethod("chat", String.class, String.class, String.class).getReturnType());
    }
}
