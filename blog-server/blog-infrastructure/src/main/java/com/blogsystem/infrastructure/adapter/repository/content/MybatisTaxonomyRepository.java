package com.blogsystem.infrastructure.adapter.repository.content;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.domain.content.model.CategoryDefinition;
import com.blogsystem.domain.content.model.TagDefinition;
import com.blogsystem.domain.content.query.CategoryView;
import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.domain.content.adapter.repository.TaxonomyRepository;
import com.blogsystem.infrastructure.mybatis.converter.content.ContentReadConverter;
import com.blogsystem.infrastructure.mybatis.mapper.content.CategoryMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.TagMapper;
import com.blogsystem.infrastructure.mybatis.record.content.CategoryRecord;
import com.blogsystem.infrastructure.mybatis.record.content.TagRecord;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class MybatisTaxonomyRepository implements TaxonomyRepository {
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    public Long saveCategory(CategoryDefinition request) {
        CategoryRecord category =
                request.id() == null
                        ? new CategoryRecord()
                        : categoryMapper.selectById(request.id());
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
        return categoryMapper
                .selectList(
                        new LambdaQueryWrapper<CategoryRecord>()
                                .eq(CategoryRecord::getDeleted, 0)
                                .orderByAsc(CategoryRecord::getSort)
                                .orderByDesc(CategoryRecord::getId))
                .stream()
                .map(ContentReadConverter::category)
                .toList();
    }

    public void deleteCategory(Long id) {
        CategoryRecord category = categoryMapper.selectById(id);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        categoryMapper.deleteById(id);
    }

    public Long saveTag(TagDefinition request) {
        TagRecord tag = request.id() == null ? new TagRecord() : tagMapper.selectById(request.id());
        if (request.id() != null && tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tag.setName(request.name());
        tag.setSlug(request.slug());
        tag.setColor(request.color());
        tag.setSort(request.sort() == null ? 0 : request.sort());
        tag.setStatus(request.status() == null ? 1 : request.status());
        if (request.isHot() != null) tag.setIsHot(request.isHot());
        else if (request.id() == null) tag.setIsHot(0);
        tag.setDeleted(0);
        if (request.id() == null) {
            tagMapper.insert(tag);
        } else {
            tagMapper.updateById(tag);
        }
        return tag.getId();
    }

    public List<TagView> listTag() {
        return tagMapper
                .selectList(
                        new LambdaQueryWrapper<TagRecord>()
                                .eq(TagRecord::getDeleted, 0)
                                .orderByAsc(TagRecord::getSort)
                                .orderByDesc(TagRecord::getId))
                .stream()
                .map(ContentReadConverter::tag)
                .toList();
    }

    public void deleteTag(Long id) {
        TagRecord tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tagMapper.deleteById(id);
    }

}
