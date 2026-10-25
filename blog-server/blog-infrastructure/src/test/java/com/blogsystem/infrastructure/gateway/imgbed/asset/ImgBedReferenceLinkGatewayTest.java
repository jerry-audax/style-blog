package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImgBedReferenceLinkGatewayTest {
    static ImgBedReferenceLinkGateway gateway() {
        return new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example"), URI.create("https://old.example")), "blog", 1048576, 10000);
    }

    @Test void decodesExactlyOnceAndIgnoresOnlyQueryAndFragment() {
        var result = gateway().parseLink("https://img.example/file/blog/中文%2B100%25.png?width=300#image");
        assertThat(result.complete()).isTrue();
        assertThat(result.objectKeys()).containsExactly(new MediaObjectKey("blog/中文+100%.png"));
    }

    @Test void literalPlusIsNotFormEncodedSpaceAndKeyCaseIsPreserved() {
        assertThat(gateway().parseLink("/file/blog/A+B.png").objectKeys()).containsExactly(new MediaObjectKey("blog/A+B.png"));
        assertThat(gateway().parseLink("https://IMG.EXAMPLE:443/file/blog/A%20B.png").objectKeys()).containsExactly(new MediaObjectKey("blog/A B.png"));
        assertThat(gateway().parseLink("https://img.example/file/blog/a.png").objectKeys()).containsExactly(new MediaObjectKey("blog/a.png"));
    }

    @Test void acceptsOnlyExplicitOriginAliasesAndTlsProtocolRelativeLinks() {
        assertThat(gateway().parseLink("//old.example/file/blog/cover.png").objectKeys()).containsExactly(new MediaObjectKey("blog/cover.png"));
        assertThat(gateway().parseLink("https://not-listed.example/file/blog/cover.png").objectKeys()).isEmpty();
        assertThat(gateway().parseLink("https://not-listed.example/file/blog/cover.png").complete()).isTrue();
        assertThat(gateway().parseLink("https://img.example.evil.test/file/blog/cover.png").objectKeys()).isEmpty();
        assertThat(gateway().parseLink("https://external.example/?next=https://img.example/file/blog/cover.png").objectKeys()).isEmpty();
    }

    @Test void generatedPublicUrlsRoundTripUnicodeSpacePercentPlusAndEmoji() {
        var urls = new ImgBedMediaPublicUrlRepository(URI.create("https://img.example"), "blog");
        for (String value : new String[]{"blog/中文+100%.png", "blog/Mixed%Name.txt", "blog/with space.jpg", "blog/音频🎧.mp3"}) {
            var key = new MediaObjectKey(value);
            var result = gateway().parseLink(urls.publicUrl("cloudflare_imgbed", key));
            assertThat(result.complete()).as(value).isTrue();
            assertThat(result.objectKeys()).as(value).containsExactly(key);
        }
    }

    @Test void encodedSeparatorsTraversalDoubleEncodingAndInvalidUtf8NeverMatch() {
        for (String tail : new String[]{"a%2Fb.png", "a%5Cb.png", "../x.png", "%2e%2e/x.png", "%252e%252e/x.png",
                "file%2541.png", "x%00.png", "a//b.png", "./x.png", "x%3Ftoken.png", "x%23fragment.png",
                "x%FF.png", "x%E4.png", "x%.png", "x%2.png", "x%GG.png", "x\\y.png", "x\ny.png", "x.png/"}) {
            var result = gateway().parseLink("https://img.example/file/blog/" + tail);
            assertThat(result.complete()).as(tail).isFalse();
            assertThat(result.objectKeys()).as(tail).isEmpty();
        }
    }

    @Test void credentialsWrongSchemePortAndNamespaceCannotMasqueradeAsManaged() {
        for (String value : new String[]{"https://user@img.example/file/blog/x.png", "http://img.example/file/blog/x.png",
                "https://img.example:444/file/blog/x.png", "https://img.example./file/blog/x.png",
                "https://img.example/file/other/x.png", "https://img.example/dashboard", "https://img.example/file/blog/"}) {
            var result = gateway().parseLink(value);
            assertThat(result.complete()).as(value).isFalse();
            assertThat(result.objectKeys()).as(value).isEmpty();
        }
    }

    @Test void noLinkAndEmbeddedDataAreNotManagedResources() {
        for (String value : new String[]{null, "", "  ", "data:image/png;base64,AAAA", "blob:https://img.example/id", "/img/local.png"}) {
            assertThat(gateway().parseLink(value).objectKeys()).isEmpty();
            assertThat(gateway().parseLink(value).complete()).isTrue();
        }
    }

    @Test void ordinaryMailAndTelephoneLinksDoNotCreateAFalseSourceFailure() {
        for (String value : new String[]{"mailto:owner@example.com", "tel:12345678"}) {
            assertThat(gateway().parseLink(value).complete()).as(value).isTrue();
            assertThat(gateway().parseLink(value).objectKeys()).isEmpty();
        }
    }

    @Test void browserEncodedRawSpacesStillReferToTheSameObject() {
        var result = gateway().parseLink("https://img.example/file/blog/A B.png");
        assertThat(result.complete()).isTrue();
        assertThat(result.objectKeys()).containsExactly(new MediaObjectKey("blog/A B.png"));
    }

    @Test void rejectsInvalidTrustedConfigurationRatherThanBroadeningTheAllowlist() {
        for (String origin : new String[]{"http://img.example", "https://img.example/path", "https://img.example?x=1", "https://u@img.example", "https://img.example#x"}) {
            assertThatThrownBy(() -> new ImgBedReferenceLinkGateway(Set.of(URI.create(origin)), "blog", 100, 10)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ImgBedReferenceLinkGateway(Set.of(), "blog", 100, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example")), "../blog", 100, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example")), "blog", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        var local = new ImgBedReferenceLinkGateway(Set.of(URI.create("http://127.0.0.1:9999")), "blog", 1000, 10);
        assertThat(local.parseLink("http://127.0.0.1:9999/file/blog/x.png").objectKeys()).containsExactly(new MediaObjectKey("blog/x.png"));
    }
}
