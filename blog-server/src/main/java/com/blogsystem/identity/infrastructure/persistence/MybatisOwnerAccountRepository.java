package com.blogsystem.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.identity.domain.model.OwnerAccount;
import com.blogsystem.identity.domain.repository.OwnerAccountRepository;
import com.blogsystem.shared.infrastructure.audit.entity.LoginLog;
import com.blogsystem.shared.infrastructure.audit.mapper.LoginLogMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class MybatisOwnerAccountRepository implements OwnerAccountRepository {
    private final SysUserMapper users;
    private final LoginLogMapper logs;

    public MybatisOwnerAccountRepository(SysUserMapper users, LoginLogMapper logs) {
        this.users = users;
        this.logs = logs;
    }

    @Override
    public Optional<OwnerAccount> findByPhone(String phone) {
        return Optional.ofNullable(users.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone))).map(this::snapshot);
    }

    @Override
    public Optional<OwnerAccount> findById(long id) {
        return Optional.ofNullable(users.selectById(id)).map(this::snapshot);
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(long id, String upgradedHash) {
        SysUser record = requireRecord(id);
        record.setLastLoginTime(LocalDateTime.now());
        if (upgradedHash != null) record.setPassword(upgradedHash);
        users.updateById(record);
    }

    @Override
    @Transactional
    public OwnerAccount updateProfile(long id, String nickname, String email, String avatar) {
        SysUser record = requireRecord(id);
        if (nickname != null) record.setNickname(nickname);
        if (email != null) record.setEmail(email);
        if (avatar != null) record.setAvatar(avatar);
        users.updateById(record);
        return snapshot(record);
    }

    @Override
    @Transactional
    public void changePassword(long id, String encodedPassword) {
        SysUser record = requireRecord(id);
        record.setPassword(encodedPassword);
        users.updateById(record);
    }

    @Override
    public void recordLoginAttempt(OwnerAccount account, boolean success, String ip, String userAgent) {
        try {
            LoginLog log = new LoginLog();
            log.setUserId(account == null ? null : account.id());
            log.setUsername(account == null ? null : account.username());
            log.setLoginStatus(success ? 1 : 0);
            log.setLoginType("password");
            log.setIp(ip);
            log.setUserAgent(userAgent);
            log.setFailReason(success ? null : "账号或密码错误");
            logs.insert(log);
        } catch (RuntimeException ignored) { /* No credentials or full request are logged. */ }
    }

    private SysUser requireRecord(long id) {
        return Optional.ofNullable(users.selectById(id)).filter(u -> Integer.valueOf(1).equals(u.getStatus()) && Integer.valueOf(0).equals(u.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("博主账户不可用"));
    }

    private OwnerAccount snapshot(SysUser u) {
        return new OwnerAccount(u.getId(), u.getPhone(), u.getUsername(), u.getNickname(), u.getEmail(), u.getAvatar(),
                u.getPassword(), Integer.valueOf(1).equals(u.getStatus()) && Integer.valueOf(0).equals(u.getDeleted()));
    }
}
