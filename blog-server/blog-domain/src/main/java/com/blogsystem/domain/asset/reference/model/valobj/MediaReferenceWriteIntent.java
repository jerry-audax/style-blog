package com.blogsystem.domain.asset.reference.model.valobj;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Original business inputs; parsing and database locking belong to the repository adapter. */
public record MediaReferenceWriteIntent(
        List<String> links, List<String> contents, List<String> htmlContents, Set<UUID> assetIds) {
    public MediaReferenceWriteIntent {
        if (links == null || contents == null || htmlContents == null || assetIds == null
                || links.stream().anyMatch(Objects::isNull)
                || contents.stream().anyMatch(Objects::isNull)
                || htmlContents.stream().anyMatch(Objects::isNull)
                || assetIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("资源引用意图不完整");
        }
        links = List.copyOf(links);
        contents = List.copyOf(contents);
        htmlContents = List.copyOf(htmlContents);
        assetIds = Set.copyOf(assetIds);
    }

    public MediaReferenceWriteIntent(List<String> links, List<String> contents, Set<UUID> assetIds) {
        this(links, contents, List.of(), assetIds);
    }

    public static MediaReferenceWriteIntent empty() {
        return new MediaReferenceWriteIntent(List.of(), List.of(), Set.of());
    }
}
