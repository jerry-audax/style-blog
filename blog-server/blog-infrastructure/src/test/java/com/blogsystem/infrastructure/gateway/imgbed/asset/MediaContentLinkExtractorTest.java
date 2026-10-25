package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan.Failure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MediaContentLinkExtractorTest {
    @Test void stringValuedCssImageFunctionsCannotBeReportedAsACompleteEmptyScan() {
        for (String function : new String[]{"image-set", "-webkit-image-set", "IMAGE-SET", "image", "cross-fade"}) {
            for (String html : new String[]{"<div style=\"background:" + function + "('/file/blog/hidden.png' 1x)\"></div>",
                    "<style>.x{background:" + function + "('/file/blog/hidden.png' 1x)}</style>"}) {
                var scan = ImgBedReferenceLinkGatewayTest.gateway().parseHtmlContent(html);
                assertThat(scan.complete()).as(html).isFalse();
                assertThat(scan.failures()).contains(Failure.UNSUPPORTED);
            }
        }
        var mixed = ImgBedReferenceLinkGatewayTest.gateway().parseHtmlContent(
                "<style>.x{background:image-set('/file/blog/hidden.png' 1x,url('/file/blog/known.png') 2x)}</style>");
        assertThat(mixed.complete()).isFalse();
        assertThat(mixed.objectKeys()).containsExactly(new MediaObjectKey("blog/known.png"));
    }
    @Test void unclosedTagChecksRespectMarkdownCodeBoundariesButRejectActualMalformedMarkup() {
        var gateway = ImgBedReferenceLinkGatewayTest.gateway();
        for (String example : new String[]{"```html\n<img\n```", "示例 `<img` 只显示代码", "# 示例\n\n    <img\n"}) {
            var scan = gateway.parseContent(example);
            assertThat(scan.complete()).as(example).isTrue();
            assertThat(scan.objectKeys()).isEmpty();
        }
        assertThat(gateway.parseContent("# 正文\n\n<img src='/file/blog/broken.png'").complete()).isFalse();
        assertThat(gateway.parseHtmlContent("    <img src='/file/blog/broken.png'").complete()).isFalse();
    }
    @Test void explicitStoredHtmlIsInspectedDirectlyEvenWhenItLooksLikeMarkdownCode() {
        var gateway = ImgBedReferenceLinkGatewayTest.gateway();
        for (String html : new String[]{"    <img src='/file/blog/indented.png'>", "正文\n    <img src='/file/blog/indented.png'>"}) {
            var scan = gateway.parseHtmlContent(html);
            assertThat(scan.complete()).isTrue();
            assertThat(scan.objectKeys()).containsExactly(new MediaObjectKey("blog/indented.png"));
        }
        assertThat(gateway.parseHtmlContent("<img src='/file/blog/broken.png'").complete()).isFalse();
        assertThat(gateway.parseHtmlContent("x".repeat(1048577)).failures()).containsExactly(Failure.LIMIT_EXCEEDED);
        assertThat(gateway.parseHtmlContent("<img src='/file/blog/x.png'>".repeat(10001)).failures()).contains(Failure.LIMIT_EXCEEDED);
    }
    @Test void tiptapHtmlDoesNotBecomeZeroReferencesJustBecauseTheColumnIsNamedMarkdown() {
        var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent("<p>正文</p><img src=\"https://img.example/file/blog/封面.png\"><video poster='/file/blog/poster.png'><source src='/file/blog/video.mp4'></video>");
        assertThat(scan.complete()).isTrue();
        assertThat(scan.objectKeys()).containsExactlyInAnyOrder(new MediaObjectKey("blog/封面.png"), new MediaObjectKey("blog/poster.png"), new MediaObjectKey("blog/video.mp4"));
    }

    @Test void resolvesMarkdownInlineAndReferenceImagesAlongsideRawHtml() {
        var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent("![inline](https://img.example/file/blog/inline.png)\n\n![引用][cover]\n\n[cover]: <https://old.example/file/blog/引用.png> \"封面\"\n\n<img src='/file/blog/html.png'>");
        assertThat(scan.complete()).isTrue();
        assertThat(scan.objectKeys()).containsExactlyInAnyOrder(new MediaObjectKey("blog/inline.png"), new MediaObjectKey("blog/引用.png"), new MediaObjectKey("blog/html.png"));
    }

    @Test void handlesHtmlEntitiesLazyAttributesSrcsetAndCssWithoutDecodingUrlsTwice() {
        var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent("""
                <figure style="background-image:url('/file/blog/css.png')">
                <a href="https://img.example/file/blog/A&amp;B.png?x=1&amp;y=2">图片</a>
                <img data-lazy-src="/file/blog/lazy.png" data-src="/file/blog/data.png"
                  srcset="/file/blog/one.png 1x, https://img.example/file/blog/two.png 2x"
                  data-srcset="/file/blog/three.png 640w" data-site-lazy-background="/file/blog/background.png">
                </figure>
                """);
        assertThat(scan.complete()).isTrue();
        assertThat(scan.objectKeys()).containsExactlyInAnyOrder(new MediaObjectKey("blog/css.png"), new MediaObjectKey("blog/A&B.png"),
                new MediaObjectKey("blog/lazy.png"), new MediaObjectKey("blog/data.png"), new MediaObjectKey("blog/one.png"),
                new MediaObjectKey("blog/two.png"), new MediaObjectKey("blog/three.png"), new MediaObjectKey("blog/background.png"));
    }

    @Test void duplicateOccurrencesDoNotDuplicateIdentityButStillConsumeTheCandidateLimit() {
        var gateway = new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example")), "blog", 10000, 2);
        var scan = gateway.parseContent("<img src='/file/blog/x.png'><img src='/file/blog/x.png'>");
        assertThat(scan.complete()).isTrue(); assertThat(scan.objectKeys()).containsExactly(new MediaObjectKey("blog/x.png"));
        var limited = gateway.parseContent("<img src='/file/blog/x.png'>".repeat(3));
        assertThat(limited.failures()).contains(Failure.LIMIT_EXCEEDED);
        assertThat(limited.objectKeys()).containsExactly(new MediaObjectKey("blog/x.png"));
    }

    @Test void failureOfOneManagedLinkDoesNotEraseTheOtherPositiveKeys() {
        var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent("<img src='/file/blog/good.png'><img src='/file/blog/%252e%252e/bad.png'>");
        assertThat(scan.complete()).isFalse(); assertThat(scan.objectKeys()).containsExactly(new MediaObjectKey("blog/good.png"));
    }

    @Test void malformedManagedMarkupAndUnsupportedCssAreNeverAnEmptySuccessfulScan() {
        var gateway = ImgBedReferenceLinkGatewayTest.gateway();
        assertThat(gateway.parseContent("<img src=\"https://img.example/file/blog/unclosed.png").complete()).isFalse();
        var scan = gateway.parseContent("<img src='/file/blog/good.png'><span style=\"background:url('\\2f file/blog/hidden.png')\"></span>");
        assertThat(scan.complete()).isFalse(); assertThat(scan.objectKeys()).contains(new MediaObjectKey("blog/good.png"));
        assertThat(gateway.parseContent("<img srcset='/file/blog/name,ambiguous.png 1x'>").complete()).isFalse();
    }

    @Test void unsupportedDocumentBaseAndDynamicResourceCodeAreNotSilentlyTrusted() {
        var gateway = ImgBedReferenceLinkGatewayTest.gateway();
        assertThat(gateway.parseContent("<base href='https://img.example/file/blog/'><img src='relative.png'>").complete()).isFalse();
        assertThat(gateway.parseContent("<script>const image = '/file/' + 'blog/x.png';</script>").complete()).isFalse();
    }

    @Test void ambiguousSrcsetDoesNotInventReferencesAndKeepsIndependentValidLinks() {
        for (String attribute : new String[]{"srcset", "data-srcset"}) {
            var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent(
                    "<img src='/file/blog/good.png' " + attribute + "='/file/blog/A.png,B.png 1x'>"
                            + "<img srcset='/file/blog/one.png 1x, /file/blog/two.png 2x'>");
            assertThat(scan.failures()).contains(Failure.UNSUPPORTED);
            assertThat(scan.objectKeys()).containsExactlyInAnyOrder(new MediaObjectKey("blog/good.png"),
                    new MediaObjectKey("blog/one.png"), new MediaObjectKey("blog/two.png"));
        }
        var encoded = ImgBedReferenceLinkGatewayTest.gateway().parseContent(
                "<img srcset='/file/blog/A.png%2CB.png 1x'>");
        assertThat(encoded.complete()).isTrue();
        assertThat(encoded.objectKeys()).containsExactly(new MediaObjectKey("blog/A.png,B.png"));
    }

    @Test void defaultDocumentAndLinkCapsAreEnforcedWithoutPretendingTheTruncatedPartWasChecked() {
        var gateway = ImgBedReferenceLinkGatewayTest.gateway();
        assertThat(gateway.parseContent("x".repeat(1048577)).failures()).containsExactly(Failure.LIMIT_EXCEEDED);
        var scan = gateway.parseContent("<img src='/file/blog/x.png'>".repeat(10001));
        assertThat(scan.failures()).contains(Failure.LIMIT_EXCEEDED);
        assertThat(scan.objectKeys()).containsExactly(new MediaObjectKey("blog/x.png"));
    }

    @Test void emptyPlainTextAndForeignResourcesDoNotInventManagedKeys() {
        for (String content : new String[]{null, "", "普通段落", "<img src='https://foreign.example/file/blog/x.png'>", "<img src='data:image/png;base64,AAAA'>"}) {
            var scan = ImgBedReferenceLinkGatewayTest.gateway().parseContent(content);
            assertThat(scan.complete()).isTrue(); assertThat(scan.objectKeys()).isEmpty();
        }
    }
}
