package com.blogsystem.domain.content.model;

import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.types.exception.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeTagPolicyTest {
    private TagView tag(long id, int sort, int hot, int status, int deleted) {
        return new TagView(id,"tag"+id,"tag-"+id,null,sort,status,null,null,deleted,hot);
    }

    @Test void choosesFirstThreeHotTagsUsingExistingSortAndStableIdTieBreak() {
        var tags=List.of(tag(1,0,0,1,0),tag(2,3,1,1,0),tag(3,1,1,1,0),tag(4,1,1,1,0),tag(5,1,1,1,0));
        assertEquals(List.of(5L,4L,3L),HomeTagPolicy.select(tags).stream().map(TagView::id).toList());
    }

    @Test void fewerThanThreeHotTagsFallsBackAsAWholeRatherThanMixingSelections() {
        var tags=List.of(tag(1,0,0,1,0),tag(2,0,0,1,0),tag(3,0,0,1,0),tag(4,99,1,1,0),tag(5,99,1,1,0));
        assertEquals(List.of(3L,2L,1L),HomeTagPolicy.select(tags).stream().map(TagView::id).toList());
    }

    @Test void disabledDeletedAndUnnamedTagsDoNotCountAndShortCatalogIsNotFabricated() {
        var tags=List.of(tag(1,0,1,1,0),tag(2,0,1,0,0),tag(3,0,1,1,1),
                new TagView(4L," ","empty",null,0,1,null,null,0,1));
        assertEquals(List.of(1L),HomeTagPolicy.select(tags).stream().map(TagView::id).toList());
        assertEquals(List.of(),HomeTagPolicy.select(List.of()));
    }

    @Test void rejectsInvalidHotFlagButOmittedFlagCanPreserveExistingTagOnUpdate() {
        assertThrows(DomainException.class,()->new TagDefinition(1L,"name","slug",null,0,1,2));
        assertThrows(DomainException.class,()->new TagDefinition(1L,"name","slug",null,0,1,-1));
        assertNull(new TagDefinition(1L,"name","slug",null,0,1).isHot());
    }
}
