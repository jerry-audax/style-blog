package com.blogsystem.infrastructure.gateway.netease;

import com.blogsystem.domain.asset.image.policy.ImageSignaturePolicy;
import com.blogsystem.domain.music.gateway.SongArtworkDownloadGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

@Repository
public class NeteaseSongArtworkDownloadRepository implements SongArtworkDownloadGateway {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final SongArtworkGateway artwork;
    private final HttpClient http;

    @Autowired public NeteaseSongArtworkDownloadRepository(SongArtworkGateway artwork) {
        this(artwork, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    NeteaseSongArtworkDownloadRepository(SongArtworkGateway artwork, HttpClient http) {
        this.artwork=artwork; this.http=http;
    }

    public Optional<MusicMediaUpload> download(String sourceId) {
        if (sourceId==null || !sourceId.matches("[1-9]\\d{0,19}")) return Optional.empty();
        String url=NeteaseSongArtworkRepository.safeCover(artwork.findCovers(Set.of(sourceId)).getOrDefault(sourceId,""));
        if(url.isBlank()) return Optional.empty();
        try {
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("Referer","https://music.163.com/").GET().build();
            var response=http.send(request, info->new LimitedBody());
            if(response.statusCode()!=200 || response.body().length==0 || response.body().length>MAX_BYTES)
                throw unavailable();
            String mime=ImageSignaturePolicy.detect(response.body());
            if(mime==null) throw unavailable();
            String suffix=switch(mime) {case "image/png"->"png";case "image/gif"->"gif";case "image/webp"->"webp";default->"jpg";};
            return Optional.of(new MusicMediaUpload(response.body(),mime,sourceId+"."+suffix,MusicMediaKind.COVER));
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch(Exception failure) { throw unavailable(); }
    }

    private static MusicCatalogException unavailable() { return new MusicCatalogException(MusicCatalogException.Reason.UNAVAILABLE); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean rejected;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if(rejected) return;
            for(var buffer:buffers) size+=buffer.remaining();
            if(size>MAX_BYTES) { rejected=true; subscription.cancel(); delegate.onError(unavailable()); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable failure) { if(!rejected) delegate.onError(failure); }
        public void onComplete() { if(!rejected) delegate.onComplete(); }
    }
}
