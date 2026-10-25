package com.blogsystem.infrastructure.gateway.ai;

import com.blogsystem.infrastructure.gateway.ai.inprocess.InProcessAiAdapter;
import com.blogsystem.infrastructure.gateway.ai.http.HttpBlogAiAdapter;
import com.blogsystem.infrastructure.gateway.ai.springai.SpringAiChatAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiStreamAdapterTest {
    @Test void chunksRemainOrderedAndCompletedStreamsCloseExactlyOnce() {
        for (boolean http : new boolean[]{false, true}) {
            var closed = new AtomicInteger();
            var source = Flux.using(Object::new, ignored -> Flux.just("a", "b"), ignored -> closed.incrementAndGet());
            assertEquals(List.of("a", "b"), JdkFlowAdapter.flowPublisherToFlux(flow(http, source))
                    .collectList().block(Duration.ofSeconds(2)));
            assertEquals(1, closed.get(), "Completed cleanup; http=" + http);
        }
    }

    @Test void streamErrorsKeepTheOriginalFailure() {
        for (boolean http : new boolean[]{false, true}) {
            var failure = new DecodingException("fake stream failure");
            var source = Flux.concat(Flux.just("a"), Flux.error(failure));
            var thrown = assertThrows(DecodingException.class, () -> JdkFlowAdapter
                    .flowPublisherToFlux(flow(http, source)).collectList().block(Duration.ofSeconds(2)));
            assertSame(failure, thrown);
        }
    }

    @Test void cancellationReachesUpstreamAndClosesExactlyOnce() {
        for (boolean http : new boolean[]{false, true}) {
            var closed = new AtomicInteger();
            var received = new ArrayList<String>();
            var subscription = new AtomicReference<Flow.Subscription>();
            var source = Flux.using(Object::new, ignored -> Flux.just("a").concatWith(Flux.never()),
                    ignored -> closed.incrementAndGet());
            flow(http, source).subscribe(new Flow.Subscriber<String>() {
                public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
                public void onNext(String value) { received.add(value); subscription.get().cancel(); }
                public void onError(Throwable failure) { fail(failure); }
                public void onComplete() { fail("Unbounded fake stream should be cancelled"); }
            });
            assertEquals(List.of("a"), received);
            assertEquals(1, closed.get());
        }
    }

    @SuppressWarnings("unchecked")
    private Flow.Publisher<String> flow(boolean http, Flux<String> source) {
        Object stream;
        if (http) {
            var buffers = new DefaultDataBufferFactory();
            var builder = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "text/event-stream")
                    // Model a non-fused HTTP body, not an in-memory synchronous queue.
                    .body(source.hide().map(chunk -> buffers.wrap(("data:" + chunk + "\n\n").getBytes(StandardCharsets.UTF_8))))
                    .build()));
            stream = new HttpBlogAiAdapter(builder, "http://fake.test").stream("owner", "session", "message");
        } else {
            var provider = mock(SpringAiChatAdapter.class);
            when(provider.chat("message", "session")).thenReturn(source);
            stream = new InProcessAiAdapter(provider, mock(ChatMemory.class)).stream("owner", "session", "message");
        }
        assertInstanceOf(Flow.Publisher.class, stream);
        return (Flow.Publisher<String>) stream;
    }
}
