package com.blogsystem.infrastructure.adapter.repository.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;
import com.blogsystem.infrastructure.mybatis.mapper.administration.LoginLogMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.infrastructure.mybatis.record.administration.LoginLogRecord;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRecord;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisOwnerAccountRepository implements OwnerAccountRepository {
    private final OwnerAccountMapper users;
    private final LoginLogMapper logs;
    private final MediaReferenceWriteRepository mediaReferences;

    public MybatisOwnerAccountRepository(OwnerAccountMapper users, LoginLogMapper logs,
            MediaReferenceWriteRepository mediaReferences) {
        this.users = users;
        this.logs = logs;
        this.mediaReferences = Objects.requireNonNull(mediaReferences);
    }

    @Override
    public Optional<OwnerAccount> findByPhone(String phone) {
        return Optional.ofNullable(
                        users.selectOne(
                                new LambdaQueryWrapper<OwnerAccountRecord>()
                                        .eq(OwnerAccountRecord::getPhone, phone)))
                .map(this::snapshot);
    }

    @Override
    public Optional<OwnerAccount> findById(long id) {
        return Optional.ofNullable(users.selectById(id)).map(this::snapshot);
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(long id, String upgradedHash) {
        requireRecord(id);
        int changed = users.update(null, new LambdaUpdateWrapper<OwnerAccountRecord>()
                .eq(OwnerAccountRecord::getId, id)
                .eq(OwnerAccountRecord::getStatus, 1)
                .eq(OwnerAccountRecord::getDeleted, 0)
                .set(OwnerAccountRecord::getLastLoginTime, LocalDateTime.now())
                .set(upgradedHash != null, OwnerAccountRecord::getPassword, upgradedHash));
        if (changed != 1) throw new IllegalArgumentException("博主账户不可用");
    }

    @Override
    @Transactional
    public OwnerAccount updateProfile(long id, String nickname, String email, String avatar) {
        OwnerAccountRecord previous = requireRecord(id);
        String desiredAvatar = avatar == null ? previous.getAvatar() : avatar;
        mediaReferences.lockForWrite(avatarReference(previous.getAvatar()), avatarReference(desiredAvatar));
        OwnerAccountRecord locked = users.selectActiveForUpdate(id);
        if (locked == null || !Objects.equals(previous.getAvatar(), locked.getAvatar())) {
            throw new MediaReferenceWriteException(MediaReferenceWriteException.Reason.CONFLICT);
        }
        if (nickname != null || email != null || avatar != null) {
            int changed = users.update(null, new LambdaUpdateWrapper<OwnerAccountRecord>()
                    .eq(OwnerAccountRecord::getId, id)
                    .eq(OwnerAccountRecord::getStatus, 1)
                    .eq(OwnerAccountRecord::getDeleted, 0)
                    .set(nickname != null, OwnerAccountRecord::getNickname, nickname)
                    .set(email != null, OwnerAccountRecord::getEmail, email)
                    .set(avatar != null, OwnerAccountRecord::getAvatar, avatar));
            if (changed != 1) throw new MediaReferenceWriteException(MediaReferenceWriteException.Reason.CONFLICT);
            return snapshot(requireRecord(id));
        }
        return snapshot(locked);
    }

    @Override
    @Transactional
    public void changePassword(long id, String encodedPassword) {
        requireRecord(id);
        int changed = users.update(null, new LambdaUpdateWrapper<OwnerAccountRecord>()
                .eq(OwnerAccountRecord::getId, id)
                .eq(OwnerAccountRecord::getStatus, 1)
                .eq(OwnerAccountRecord::getDeleted, 0)
                .set(OwnerAccountRecord::getPassword, encodedPassword));
        if (changed != 1) throw new IllegalArgumentException("博主账户不可用");
    }

    @Override
    public void recordLoginAttempt(
            OwnerAccount account, boolean success, String ip, String userAgent) {
        try {
            LoginLogRecord log = new LoginLogRecord();
            log.setUserId(account == null ? null : account.id());
            log.setUsername(account == null ? null : account.username());
            log.setLoginStatus(success ? 1 : 0);
            log.setLoginType("password");
            log.setIp(ip);
            log.setUserAgent(userAgent);
            log.setFailReason(success ? null : "账号或密码错误");
            logs.insert(log);
        } catch (RuntimeException ignored) {
            /* No credentials or full request are logged. */
        }
    }

    private OwnerAccountRecord requireRecord(long id) {
        return Optional.ofNullable(users.selectById(id))
                .filter(
                        u ->
                                Integer.valueOf(1).equals(u.getStatus())
                                        && Integer.valueOf(0).equals(u.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("博主账户不可用"));
    }

    private static MediaReferenceWriteIntent avatarReference(String avatar) {
        return new MediaReferenceWriteIntent(avatar == null ? List.of() : List.of(avatar), List.of(), Set.of());
    }

    private OwnerAccount snapshot(OwnerAccountRecord u) {
        return new OwnerAccount(
                u.getId(),
                u.getPhone(),
                u.getUsername(),
                u.getNickname(),
                u.getEmail(),
                u.getAvatar(),
                u.getPassword(),
                Integer.valueOf(1).equals(u.getStatus())
                        && Integer.valueOf(0).equals(u.getDeleted()));
    }
}
