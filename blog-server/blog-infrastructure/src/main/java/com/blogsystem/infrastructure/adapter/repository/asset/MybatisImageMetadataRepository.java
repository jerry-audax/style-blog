package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.image.model.query.ImageMetadataQuery;
import com.blogsystem.domain.asset.image.repository.ImageMetadataRepository;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.ImageMetadataMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
public class MybatisImageMetadataRepository implements ImageMetadataRepository {
    private final ImageMetadataMapper mapper;
    private final MediaAssetConverter converter;

    public MybatisImageMetadataRepository(ImageMetadataMapper mapper, MediaAssetConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override public MediaAssetPage findPage(ImageMetadataQuery query) {
        if (query == null) throw new IllegalArgumentException("Image query required");
        try {
            long total = mapper.count(query);
            var records = mapper.findPage(query).stream().map(converter::toDomain).toList();
            return new MediaAssetPage(records, total, query.offset(), query.limit());
        } catch (RuntimeException failure) {
            throw new MediaAssetReadException();
        }
    }
}
