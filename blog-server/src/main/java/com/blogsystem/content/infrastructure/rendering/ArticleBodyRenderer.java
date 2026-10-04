package com.blogsystem.content.infrastructure.rendering;

import com.blogsystem.content.domain.model.ContentBody;
import com.blogsystem.content.domain.model.ContentFormat;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * HTML from the editor is preserved; historical Markdown is rendered in the adapter.
 */
@Component
public class ArticleBodyRenderer {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String render(ContentBody body) {
        return body.format() == ContentFormat.HTML ? body.value() : renderer.render(parser.parse(body.value()));
    }
}
