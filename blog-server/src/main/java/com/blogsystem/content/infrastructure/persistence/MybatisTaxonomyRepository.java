package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.content.domain.model.CategoryDefinition;
import com.blogsystem.content.domain.model.TagDefinition;
import com.blogsystem.content.domain.query.*;
import com.blogsystem.content.domain.repository.TaxonomyRepository;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class MybatisTaxonomyRepository implements TaxonomyRepository {
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    public Long saveCategory(CategoryDefinition request) {
        Category category = request.id() == null ? new Category() : categoryMapper.selectById(request.id());
        if (request.id() != null && category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        category.setName(request.name());
        category.setSlug(request.slug());
        category.setDescription(request.description());
        category.setSort(request.sort() == null ? 0 : request.sort());
        category.setStatus(request.status() == null ? 1 : request.status());
        category.setDeleted(0);
        if (request.id() == null) {
            categoryMapper.insert(category);
        } else {
            categoryMapper.updateById(category);
        }
        return category.getId();
    }

    public List<CategoryView> listCategory() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>().eq(Category::getDeleted, 0)
                .orderByAsc(Category::getSort).orderByDesc(Category::getId)).stream().map(ContentReadMapper::category).toList();
    }

    public void deleteCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        categoryMapper.deleteById(id);
    }

    public Long saveTag(TagDefinition request) {
        Tag tag = request.id() == null ? new Tag() : tagMapper.selectById(request.id());
        if (request.id() != null && tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tag.setName(request.name());
        tag.setSlug(request.slug());
        tag.setColor(request.color());
        tag.setSort(request.sort() == null ? 0 : request.sort());
        tag.setStatus(request.status() == null ? 1 : request.status());
        tag.setDeleted(0);
        if (request.id() == null) {
            tagMapper.insert(tag);
        } else {
            tagMapper.updateById(tag);
        }
        return tag.getId();
    }

    public List<TagView> listTag() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().eq(Tag::getDeleted, 0)
                .orderByAsc(Tag::getSort).orderByDesc(Tag::getId)).stream().map(ContentReadMapper::tag).toList();
    }

    public void deleteTag(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tagMapper.deleteById(id);
    }
}
