package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan.Failure;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded Markdown/HTML inspection; no scripts, styles or remote documents are executed. */
final class MediaContentLinkExtractor {
    private static final Set<String> SINGLE_ATTRIBUTES = Set.of("src", "data-src", "data-lazy-src", "poster", "href", "xlink:href", "data-site-lazy-background");
    private static final Set<String> DYNAMIC_TAGS = Set.of("base", "script", "iframe", "object", "embed", "template");
    private static final Pattern HTML_START = Pattern.compile("(?i)<\\s*(?:img|video|audio|source|p|a|div|figure|span|style|base|script)\\b");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\s*\\(");
    // These image functions can carry string URLs outside url(); a partial URL scan is not complete evidence.
    private static final Pattern CSS_UNSUPPORTED_IMAGE = Pattern.compile("(?i)(?<![\\w-])(?:-webkit-)?(?:image-set|image|cross-fade)\\s*\\(");
    private final int maxContentChars;
    private final int maxLinks;

    MediaContentLinkExtractor(int maxContentChars, int maxLinks) {
        this.maxContentChars = maxContentChars;
        this.maxLinks = maxLinks;
    }

    MediaLinkScan extract(String text, Function<String, MediaLinkScan> resolver) {
        return extract(text, resolver, false);
    }

    MediaLinkScan extractHtml(String text, Function<String, MediaLinkScan> resolver) {
        return extract(text, resolver, true);
    }

    private MediaLinkScan extract(String text, Function<String, MediaLinkScan> resolver, boolean knownHtml) {
        if (text == null || text.isBlank()) return new MediaLinkScan(Set.of(), Set.of());
        if (text.length() > maxContentChars) return new MediaLinkScan(Set.of(), Set.of(Failure.LIMIT_EXCEEDED));
        Scan scan = new Scan(resolver);
        String html;
        if (knownHtml) {
            if (hasUnclosedTag(text)) scan.failures.add(Failure.AMBIGUOUS);
            html = text;
        } else {
            Node document = Parser.builder().build().parse(text);
            if (hasUnclosedTag(withoutMarkdownCode(text, document))) scan.failures.add(Failure.AMBIGUOUS);
            // Render the original AST; masking is only for malformed-markup detection.
            html = HtmlRenderer.builder().build().render(document);
        }
        for (Element element : Jsoup.parse(html).getAllElements()) {
            String tag = element.normalName();
            if (DYNAMIC_TAGS.contains(tag) || tag.contains("-") || tag.equals("link") && element.attr("rel").equalsIgnoreCase("stylesheet")) {
                scan.failures.add(Failure.UNSUPPORTED);
            }
            for (var attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith("on")) scan.failures.add(Failure.UNSUPPORTED);
                if (SINGLE_ATTRIBUTES.contains(name)) scan.add(attribute.getValue());
                else if (name.equals("srcset") || name.equals("data-srcset")) srcset(attribute.getValue(), scan);
                else if (name.equals("style")) css(attribute.getValue(), scan);
                if (scan.exhausted()) break;
            }
            if (tag.equals("style")) css(element.data(), scan);
            if (scan.exhausted()) break;
        }
        return new MediaLinkScan(scan.keys, scan.failures);
    }

    private static String withoutMarkdownCode(String text, Node document) {
        char[] markup = text.toCharArray();
        var pending = new ArrayDeque<Node>();
        pending.push(document);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                Arrays.fill(markup, node.getStartOffset(), node.getEndOffset(), ' ');
                continue;
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) pending.push(child);
        }
        return new String(markup);
    }

    private final class Scan {
        private final Function<String, MediaLinkScan> resolver;
        private final Set<MediaObjectKey> keys = new HashSet<>();
        private final Set<Failure> failures = EnumSet.noneOf(Failure.class);
        private int candidates;

        private Scan(Function<String, MediaLinkScan> resolver) { this.resolver = resolver; }
        private void add(String value) {
            if (value == null || value.isBlank()) return;
            if (++candidates > maxLinks) { failures.add(Failure.LIMIT_EXCEEDED); return; }
            MediaLinkScan result = resolver.apply(value);
            keys.addAll(result.objectKeys()); failures.addAll(result.failures());
        }
        private boolean exhausted() { return candidates > maxLinks; }
    }

    private static void srcset(String value, Scan scan) {
        if (value.isBlank()) return;
        // Encoded commas in a key are unambiguous; unspaced raw commas may be URL characters.
        if (Pattern.compile(",(?=\\S)").matcher(value).find()) {
            scan.failures.add(Failure.UNSUPPORTED);
            return; // Ambiguous URL delimiters cannot provide trustworthy positive identities.
        }
        String[] candidates = value.split(",", -1);
        for (String candidate : candidates) {
            String[] tokens = candidate.strip().split("\\s+");
            if (tokens.length > 2 || tokens[0].isEmpty() || candidates.length > 1 && tokens.length != 2
                    || tokens.length == 2 && !tokens[1].matches("(?:[1-9][0-9]*w|(?:[1-9][0-9]*(?:\\.[0-9]+)?|0?\\.[0-9]+)x)")) {
                scan.failures.add(Failure.UNSUPPORTED);
            }
            if (!tokens[0].isEmpty()) scan.add(tokens[0]);
            if (scan.exhausted()) return;
        }
    }

    private static void css(String value, Scan scan) {
        if (value.contains("\\") || value.toLowerCase(Locale.ROOT).contains("@import")
                || CSS_UNSUPPORTED_IMAGE.matcher(value).find()) scan.failures.add(Failure.UNSUPPORTED);
        var matcher = CSS_URL.matcher(value);
        int offset = 0;
        while (matcher.find(offset)) {
            int cursor = matcher.end();
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
            if (cursor == value.length()) { scan.failures.add(Failure.UNSUPPORTED); return; }
            char quote = value.charAt(cursor);
            boolean quoted = quote == '\'' || quote == '"';
            int start = quoted ? ++cursor : cursor;
            while (cursor < value.length() && value.charAt(cursor) != (quoted ? quote : ')')) cursor++;
            if (cursor == value.length()) { scan.failures.add(Failure.UNSUPPORTED); return; }
            String link = value.substring(start, cursor).strip();
            if (quoted) {
                cursor++;
                while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
                if (cursor == value.length() || value.charAt(cursor) != ')') { scan.failures.add(Failure.UNSUPPORTED); return; }
            } else if (link.chars().anyMatch(Character::isWhitespace) || link.indexOf('(') >= 0 || link.indexOf('\'') >= 0 || link.indexOf('"') >= 0) {
                scan.failures.add(Failure.UNSUPPORTED); return;
            }
            scan.add(link);
            if (scan.exhausted()) return;
            offset = cursor + 1;
        }
    }

    private static boolean hasUnclosedTag(String text) {
        var matcher = HTML_START.matcher(text);
        int offset = 0;
        while (matcher.find(offset)) {
            char quote = 0;
            int cursor = matcher.end();
            for (; cursor < text.length(); cursor++) {
                char value = text.charAt(cursor);
                if (quote != 0) { if (value == quote) quote = 0; }
                else if (value == '\'' || value == '"') quote = value;
                else if (value == '>') break;
            }
            if (cursor == text.length()) return true;
            offset = cursor + 1;
        }
        return false;
    }
}
