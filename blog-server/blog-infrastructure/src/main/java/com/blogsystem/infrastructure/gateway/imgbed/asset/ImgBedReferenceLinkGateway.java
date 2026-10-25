package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan.Failure;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Resolves only configured origins; never fetches a URL or normalizes an object path. */
public final class ImgBedReferenceLinkGateway implements MediaReferenceLinkGateway {
    private record Origin(String scheme, String host, int port) {}

    private final Set<Origin> origins;
    private final String root;
    private final int maxContentChars;
    private final MediaContentLinkExtractor contents;

    public ImgBedReferenceLinkGateway(Set<URI> allowedOrigins, String root, int maxContentChars, int maxLinks) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || root == null
                || !root.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*") || maxContentChars < 1 || maxLinks < 1) {
            throw new IllegalArgumentException("Invalid managed reference configuration");
        }
        Set<Origin> checked = new HashSet<>();
        for (URI origin : allowedOrigins) checked.add(configuredOrigin(origin));
        this.origins = Set.copyOf(checked);
        this.root = root;
        this.maxContentChars = maxContentChars;
        this.contents = new MediaContentLinkExtractor(maxContentChars, maxLinks);
    }

    @Override public MediaLinkScan parseLink(String value) {
        if (value == null || value.isBlank()) return empty();
        if (value.length() > maxContentChars) return failed(Failure.LIMIT_EXCEEDED);
        String link = value.strip();
        try {
            if (link.chars().anyMatch(Character::isISOControl) || link.contains("\\")) return failed(Failure.AMBIGUOUS);
            String encodedSpaces = link.replace(" ", "%20");
            URI uri = URI.create(encodedSpaces.startsWith("//") ? "https:" + encodedSpaces : encodedSpaces);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (Set.of("data", "blob", "mailto", "tel").contains(scheme)) return empty();
            if (scheme.equals("javascript")) return failed(Failure.UNSUPPORTED);
            if (uri.getRawUserInfo() != null) return failed(Failure.AMBIGUOUS);
            if (uri.isAbsolute()) {
                if (uri.getHost() == null) return failed(Failure.AMBIGUOUS);
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                boolean knownHost = origins.stream().anyMatch(origin -> origin.host().equals(host)
                        || (host.endsWith(".") && origin.host().equals(host.substring(0, host.length() - 1))));
                if (!knownHost) return empty();
                if (!origins.contains(new Origin(scheme, host, effectivePort(uri)))) return failed(Failure.UNSUPPORTED);
            } else if (!link.startsWith("/file/")) {
                return empty();
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/file/")) return failed(Failure.UNSUPPORTED);
            String[] segments = path.substring("/file/".length()).split("/", -1);
            StringBuilder keyText = new StringBuilder();
            for (String segment : segments) {
                if (segment.matches("(?i).*%(?:2f|5c).*")) return failed(Failure.AMBIGUOUS);
                String decoded = decodeSegment(segment);
                // A second decode must not select another file or directory.
                if (decoded.matches("(?s).*%[0-9a-fA-F]{2}.*")) return failed(Failure.AMBIGUOUS);
                if (!keyText.isEmpty()) keyText.append('/');
                else if (decoded.isEmpty()) return failed(Failure.AMBIGUOUS);
                keyText.append(decoded);
            }
            MediaObjectKey key = new MediaObjectKey(keyText.toString());
            key.requireWithin(root);
            return new MediaLinkScan(Set.of(key), Set.of());
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            return failed(Failure.AMBIGUOUS);
        }
    }

    @Override public MediaLinkScan parseContent(String value) { return contents.extract(value, this::parseLink); }
    @Override public MediaLinkScan parseHtmlContent(String value) { return contents.extractHtml(value, this::parseLink); }

    private static Origin configuredOrigin(URI value) {
        if (value == null || value.getScheme() == null || value.getHost() == null || value.getRawUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null || !value.getRawPath().matches("/?")) {
            throw new IllegalArgumentException("Invalid managed reference origin");
        }
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http") && host.equals("127.0.0.1"))
                || host.endsWith(".") || effectivePort(value) < 1 || effectivePort(value) > 65535) {
            throw new IllegalArgumentException("Invalid managed reference origin");
        }
        return new Origin(scheme, host, effectivePort(value));
    }

    private static int effectivePort(URI value) {
        return value.getPort() >= 0 ? value.getPort() : "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private static String decodeSegment(String value) throws CharacterCodingException {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character != '%') {
                if (Character.isHighSurrogate(character)) {
                    if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) throw new IllegalArgumentException();
                    decoded.append(character).append(value.charAt(index + 1)); index += 2;
                } else {
                    if (Character.isLowSurrogate(character)) throw new IllegalArgumentException();
                    decoded.append(character); index++;
                }
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < value.length() && value.charAt(index) == '%') {
                if (index + 2 >= value.length()) throw new IllegalArgumentException();
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException();
                bytes.write((high << 4) + low); index += 3;
            }
            decoded.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
        }
        return decoded.toString();
    }

    private static MediaLinkScan empty() { return new MediaLinkScan(Set.of(), Set.of()); }
    private static MediaLinkScan failed(Failure failure) { return new MediaLinkScan(Set.of(), Set.of(failure)); }
}
