package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.model.CategoryDefinition;
import com.blogsystem.content.domain.model.TagDefinition;
import com.blogsystem.content.domain.query.CategoryView;
import com.blogsystem.content.domain.query.TagView;

import java.util.List;

public interface TaxonomyRepository {
    Long saveCategory(CategoryDefinition category);

    List<CategoryView> listCategory();

    void deleteCategory(Long id);

    Long saveTag(TagDefinition tag);

    List<TagView> listTag();

    void deleteTag(Long id);
}

