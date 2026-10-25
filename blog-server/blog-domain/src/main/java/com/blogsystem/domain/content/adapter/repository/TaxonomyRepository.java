package com.blogsystem.domain.content.adapter.repository;

import com.blogsystem.domain.content.model.CategoryDefinition;
import com.blogsystem.domain.content.model.TagDefinition;
import com.blogsystem.domain.content.query.CategoryView;
import com.blogsystem.domain.content.query.TagView;

import java.util.List;

public interface TaxonomyRepository {
    Long saveCategory(CategoryDefinition category);

    List<CategoryView> listCategory();

    void deleteCategory(Long id);

    Long saveTag(TagDefinition tag);

    List<TagView> listTag();

    void deleteTag(Long id);
}
