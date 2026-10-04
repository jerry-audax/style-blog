package com.blogsystem.identity.infrastructure.persistence;

import com.blogsystem.identity.domain.model.PhoneNumber;
import com.blogsystem.identity.domain.model.User;
import com.blogsystem.identity.domain.model.UserId;
import com.blogsystem.identity.domain.model.UserStatus;
import com.blogsystem.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class MybatisUserRepository implements UserRepository {
    private final SysUserMapper mapper;

    public MybatisUserRepository(SysUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(mapper.selectById(id.value())).map(this::toDomain);
    }

    @Override
    public User save(User user) {
        SysUser record = mapper.selectById(user.id().value());
        boolean insert = record == null;
        if (record == null) {
            record = new SysUser();
            record.setId(user.id().value());
            record.setPhone(user.phone().value());
            record.setCreatedAt(java.time.LocalDateTime.ofInstant(user.registeredAt(), ZoneOffset.UTC));
        }
        record.setStatus(user.status() == UserStatus.ACTIVE ? 1 : 0);
        record.setUpdatedAt(java.time.LocalDateTime.ofInstant(user.updatedAt(), ZoneOffset.UTC));
        if (insert) mapper.insert(record);
        else mapper.updateById(record);
        return user;
    }

    private User toDomain(SysUser record) {
        Instant registered = Optional.ofNullable(record.getCreatedAt()).orElse(java.time.LocalDateTime.now()).toInstant(ZoneOffset.UTC);
        User user = User.register(new UserId(record.getId()), new PhoneNumber(record.getPhone()), registered);
        Instant updated = Optional.ofNullable(record.getUpdatedAt())
                .orElse(record.getCreatedAt() != null ? record.getCreatedAt() : java.time.LocalDateTime.now())
                .toInstant(ZoneOffset.UTC);
        if (record.getStatus() != null && record.getStatus() == 0) user.disable(updated);
        return user;
    }
}
