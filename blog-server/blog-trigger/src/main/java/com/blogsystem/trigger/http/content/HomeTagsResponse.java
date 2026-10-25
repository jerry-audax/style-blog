package com.blogsystem.trigger.http.content;

import com.blogsystem.domain.content.query.TagView;
import java.util.List;
import java.util.stream.IntStream;

/** The server resolves hot/default selection; the public client only renders. */
public record HomeTagsResponse(List<Item> tags) {
    public record Item(String id, String name, String slug, int position, String href) {}

    public static HomeTagsResponse from(List<TagView> tags) {
        return new HomeTagsResponse(IntStream.range(0,tags.size()).mapToObj(index -> {
            var tag=tags.get(index);
            return new Item(Long.toString(tag.id()),tag.name(),tag.slug(),index+1,"/blog/tags/?tagId="+tag.id());
        }).toList());
    }
}
