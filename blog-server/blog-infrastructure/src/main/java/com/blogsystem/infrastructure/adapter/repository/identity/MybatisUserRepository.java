package com.blogsystem.infrastructure.adapter.repository.identity;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.domain.identity.model.PhoneNumber;
import com.blogsystem.domain.identity.model.User;
import com.blogsystem.domain.identity.model.UserId;
import com.blogsystem.domain.identity.model.UserStatus;
import com.blogsystem.domain.identity.adapter.repository.UserRepository;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRecord;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class MybatisUserRepository implements UserRepository {
    private final OwnerAccountMapper mapper;

    public MybatisUserRepository(OwnerAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(mapper.selectById(id.value())).map(this::toDomain);
    }

    @Override
    public User save(User user) {
        OwnerAccountRecord record = mapper.selectById(user.id().value());
        boolean insert = record == null;
        if (record == null) {
            record = new OwnerAccountRecord();
            record.setId(user.id().value());
            record.setPhone(user.phone().value());
            record.setCreatedAt(
                    java.time.LocalDateTime.ofInstant(user.registeredAt(), ZoneOffset.UTC));
        }
        record.setStatus(user.status() == UserStatus.ACTIVE ? 1 : 0);
        record.setUpdatedAt(java.time.LocalDateTime.ofInstant(user.updatedAt(), ZoneOffset.UTC));
        if (insert) mapper.insert(record);
        else mapper.update(null, new LambdaUpdateWrapper<OwnerAccountRecord>()
                .eq(OwnerAccountRecord::getId, record.getId())
                .set(OwnerAccountRecord::getStatus, record.getStatus())
                .set(OwnerAccountRecord::getUpdatedAt, record.getUpdatedAt()));
        return user;
    }

    private User toDomain(OwnerAccountRecord record) {
        Instant registered =
                Optional.ofNullable(record.getCreatedAt())
                        .orElse(java.time.LocalDateTime.now())
                        .toInstant(ZoneOffset.UTC);
        User user =
                User.register(
                        new UserId(record.getId()), new PhoneNumber(record.getPhone()), registered);
        Instant updated =
                Optional.ofNullable(record.getUpdatedAt())
                        .orElse(
                                record.getCreatedAt() != null
                                        ? record.getCreatedAt()
                                        : java.time.LocalDateTime.now())
                        .toInstant(ZoneOffset.UTC);
        if (record.getStatus() != null && record.getStatus() == 0) user.disable(updated);
        return user;
    }
}
