package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaPublicationPinConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaPublicationPinMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MybatisMediaPublicationPinRepository implements MediaPublicationPinRepository {
    private final MediaPublicationPinMapper mapper;
    private final MediaPublicationPinConverter converter;

    public MybatisMediaPublicationPinRepository(MediaPublicationPinMapper mapper, MediaPublicationPinConverter converter) {
        this.mapper = mapper; this.converter = converter;
    }

    @Override public MediaPublicationPin insertHeld(MediaPublicationPin pin) {
        if (pin == null || pin.status() != MediaPublicationPin.Status.HELD || pin.version() != 0) throw invalid();
        try { return converter.toDomain(mapper.insert(converter.toRecord(pin))); }
        catch (DataAccessException | PersistenceException failure) { throw translated(failure); }
    }

    @Override public Optional<MediaPublicationPin> findByKey(MediaPublicationPinKey key) {
        if (key == null) throw invalid();
        try { return Optional.ofNullable(converter.toDomain(mapper.selectByKey(key))); }
        catch (DataAccessException | PersistenceException failure) { throw translated(failure); }
    }

    @Override public List<MediaPublicationPin> findHolding(UUID assetId) {
        if (assetId == null) throw invalid();
        try { return mapper.selectHolding(new UUID[]{assetId}).stream().map(converter::toDomain).toList(); }
        catch (DataAccessException | PersistenceException failure) { throw translated(failure); }
    }

    @Override public MediaPublicationPin release(MediaPublicationPinKey key, long expectedVersion, Instant now) {
        if (key == null || expectedVersion < 0 || now == null) throw invalid();
        MediaPublicationPin pin;
        try { pin = converter.toDomain(mapper.release(key, expectedVersion, MediaPublicationPinConverter.at(now.truncatedTo(ChronoUnit.MICROS)))); }
        catch (DataAccessException | PersistenceException failure) { throw translated(failure); }
        if (pin == null) throw new MediaPublicationPinException(MediaPublicationPinException.Reason.CONFLICT);
        return pin;
    }

    private static MediaPublicationPinException invalid() { return new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST); }

    private static MediaPublicationPinException translated(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return new MediaPublicationPinException(MediaPublicationPinException.Reason.CONFLICT);
            }
        }
        return new MediaPublicationPinException(MediaPublicationPinException.Reason.UNAVAILABLE);
    }
}
