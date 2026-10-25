package com.blogsystem.domain.content.model;

import com.blogsystem.domain.content.query.TagView;
import java.util.Comparator;
import java.util.List;

/** Curated hot tags are reusable; only the homepage applies the three-entry rule. */
public final class HomeTagPolicy {
    private HomeTagPolicy() {}

    public static List<TagView> select(List<TagView> tags) {
        var available = tags.stream()
                .filter(tag -> tag.id() != null && tag.id() > 0)
                .filter(tag -> Integer.valueOf(1).equals(tag.status()) && Integer.valueOf(0).equals(tag.deleted()))
                .filter(tag -> tag.name() != null && !tag.name().isBlank())
                .sorted(Comparator.comparingInt((TagView tag) -> tag.sort() == null ? 0 : tag.sort())
                        .thenComparing(TagView::id, Comparator.reverseOrder()))
                .toList();
        var hot = available.stream().filter(tag -> Integer.valueOf(1).equals(tag.isHot())).limit(3).toList();
        return hot.size() == 3 ? hot : available.stream().limit(3).toList();
    }
}
