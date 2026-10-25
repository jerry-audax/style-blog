package com.blogsystem.domain.asset.publication;

import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationResourceIdentity;
import com.blogsystem.domain.asset.publication.model.valobj.ResolvedMediaPublicationManifest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaPublicationManifestTest {
    @Test void inputsAreImmutableAndCountLimitAppliesAcrossAllCollections() {
        var links = new ArrayList<>(List.of("/file/blog/a.png"));
        var input = new MediaPublicationManifestInput("a".repeat(64), links, List.of(), List.of());
        links.clear(); assertEquals(List.of("/file/blog/a.png"), input.links());
        assertThrows(UnsupportedOperationException.class, () -> input.links().clear());
        limited(() -> new MediaPublicationManifestInput("a".repeat(64), Collections.nCopies(5000, ""), Collections.nCopies(5000, ""), List.of("")));
    }

    @Test void stringLimitIsUtf8BytesAndRejectsMissingOrMalformedFields() {
        limited(() -> new MediaPublicationManifestInput("a".repeat(64), List.of("中".repeat(349526)), List.of(), List.of()));
        invalid(() -> new MediaPublicationManifestInput("bad", List.of(), List.of(), List.of()));
        invalid(() -> new MediaPublicationManifestInput("a".repeat(64), null, List.of(), List.of()));
        invalid(() -> new MediaPublicationManifestInput("a".repeat(64), java.util.Arrays.asList((String) null), List.of(), List.of()));
    }

    @Test void oneUuidCannotRepresentTwoDifferentResourceIdentities() {
        UUID id = UUID.randomUUID();
        var first = new MediaPublicationResourceIdentity(id, "cloudflare_imgbed", new MediaObjectKey("blog/a.png"), MediaKind.IMAGE);
        var second = new MediaPublicationResourceIdentity(id, "cloudflare_imgbed", new MediaObjectKey("blog/b.png"), MediaKind.IMAGE);
        invalid(() -> new ResolvedMediaPublicationManifest("a".repeat(64), Set.of(first, second)));
        invalid(() -> new ResolvedMediaPublicationManifest("invalid", Set.of()));
        invalid(() -> new MediaPublicationResourceIdentity(null, "cloudflare_imgbed", new MediaObjectKey("blog/a.png"), MediaKind.IMAGE));
    }

    private static void invalid(Runnable action) {
        assertEquals(MediaPublicationPinException.Reason.INVALID_REQUEST,
                assertThrows(MediaPublicationPinException.class, action::run).reason());
    }

    @Test void resolvedResourceLimitIsDistinctFromMalformedIdentity() {
        var resources = new java.util.HashSet<MediaPublicationResourceIdentity>();
        for (int index = 1; index <= 10001; index++) resources.add(new MediaPublicationResourceIdentity(new UUID(0, index),
                "cloudflare_imgbed", new MediaObjectKey("blog/covers/" + index + ".png"), MediaKind.IMAGE));
        limited(() -> new ResolvedMediaPublicationManifest("a".repeat(64), resources));
    }

    private static void limited(Runnable action) {
        assertEquals("LIMIT_EXCEEDED", assertThrows(MediaPublicationPinException.class, action::run).reason().name());
    }
}
