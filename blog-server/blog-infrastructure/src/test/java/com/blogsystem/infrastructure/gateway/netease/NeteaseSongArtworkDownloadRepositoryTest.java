package com.blogsystem.infrastructure.gateway.netease;

import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NeteaseSongArtworkDownloadRepositoryTest {
    @Test void artworkAdapterCanBeWiredWithTheRealRepositoryExceptionTranslationProxy() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(SongArtworkGateway.class,()->metadata);
            context.registerBean(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class,()->{
                var processor=new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);return processor;
            });
            context.register(NeteaseSongArtworkDownloadRepository.class);
            context.refresh();
            assertNotNull(context.getBean(com.blogsystem.domain.music.gateway.SongArtworkDownloadGateway.class));
        }
    }
    final SongArtworkGateway metadata=mock(SongArtworkGateway.class);
    final HttpClient http=mock(HttpClient.class);
    final NeteaseSongArtworkDownloadRepository repository=new NeteaseSongArtworkDownloadRepository(metadata,http);

    @Test void downloadsKnownArtworkAndUsesSignatureRatherThanUntrustedContentType() throws Exception {
        when(metadata.findCovers(Set.of("123"))).thenReturn(Map.of("123","https://p1.music.126.net/test.jpg"));
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{(byte)255,(byte)216,(byte)255,0});
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        var image=repository.download("123").orElseThrow();
        assertEquals("image/jpeg",image.mediaType());
        assertEquals("123.jpg",image.filename());
    }

    @Test void refusesArbitraryHostsAndMalformedSongIdsWithoutFetchingThem() {
        for(String url:List.of("http://p1.music.126.net/x.jpg","https://127.0.0.1/x.jpg",
                "https://p1.music.126.net.evil.test/x.jpg","https://p1.music.126.net/x.jpg?redirect=1")) {
            when(metadata.findCovers(Set.of("123"))).thenReturn(Map.of("123",url));
            assertTrue(repository.download("123").isEmpty());
        }
        assertTrue(repository.download("../song").isEmpty());
        verifyNoInteractions(http);
    }

    @Test void rejectsRedirectsAndNonImagesInsteadOfUploadingAnErrorPage() throws Exception {
        when(metadata.findCovers(Set.of("123"))).thenReturn(Map.of("123","https://p1.music.126.net/test.jpg"));
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        when(response.statusCode()).thenReturn(302);
        assertThrows(MusicCatalogException.class,()->repository.download("123"));
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("<html>failure</html>".getBytes());
        assertThrows(MusicCatalogException.class,()->repository.download("123"));
    }
}
