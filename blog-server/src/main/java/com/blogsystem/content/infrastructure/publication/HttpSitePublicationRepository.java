package com.blogsystem.content.infrastructure.publication;

import com.blogsystem.content.domain.model.SitePublicationUnavailable;
import com.blogsystem.content.domain.query.SitePublicationView;
import com.blogsystem.content.domain.repository.SitePublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

public class HttpSitePublicationRepository implements SitePublicationRepository {
    private final ObjectMapper json;
    private final URI base;
    private final Supplier<String> credential;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpSitePublicationRepository(ObjectMapper json, URI base, Supplier<String> credential) {
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid publication service address");
        this.json = json; this.base = base; this.credential = credential;
    }
    @Override public SitePublicationView status() {
        try {return request(false);} catch (SitePublicationUnavailable ignored) {return SitePublicationView.unavailable();}
    }
    @Override public SitePublicationView retry() {return request(true);}

    private SitePublicationView request(boolean retry) {
        try {
            String token = credential.get();
            if (token == null || token.strip().length() < 32) throw new SitePublicationUnavailable();
            var builder = HttpRequest.newBuilder(base.resolve(retry ? "/internal/retry" : "/internal/status"))
                    .header("X-Publication-Key", token.strip()).timeout(Duration.ofSeconds(5));
            var response = client.send(retry ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != (retry ? 202 : 200) || response.body().length() > 16384) throw new SitePublicationUnavailable();
            var view = json.readValue(response.body(), SitePublicationView.class);
            if (!view.configured() || !Set.of("WAITING", "BUILDING", "CURRENT", "FAILED").contains(view.phase())) throw new SitePublicationUnavailable();
            return view;
        } catch (InterruptedException exception) {Thread.currentThread().interrupt(); throw new SitePublicationUnavailable();}
        catch (Exception exception) {throw new SitePublicationUnavailable();}
    }
}
