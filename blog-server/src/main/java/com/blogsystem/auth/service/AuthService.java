package com.blogsystem.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.auth.dto.LoginUserVO;
import com.blogsystem.auth.dto.PasswordLoginRequest;
import com.blogsystem.auth.dto.PhoneCodeAuthRequest;
import com.blogsystem.auth.dto.ResetPasswordRequest;
import com.blogsystem.common.RedisFailurePolicy;
import com.blogsystem.auth.dto.SendSmsCodeRequest;
import com.blogsystem.auth.entity.SmsCodeLog;
import com.blogsystem.auth.entity.SysRole;
import com.blogsystem.auth.entity.SysUser;
import com.blogsystem.auth.entity.SysUserRole;
import com.blogsystem.auth.mapper.SmsCodeLogMapper;
import com.blogsystem.auth.mapper.SysRoleMapper;
import com.blogsystem.auth.mapper.SysUserMapper;
import com.blogsystem.auth.mapper.SysUserRoleMapper;
import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.log.entity.LoginLog;
import com.blogsystem.log.mapper.LoginLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证业务 —— 验证码 / 注册 / 登录 / 个人信息 / 登出
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SmsCodeLogMapper smsCodeLogMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailurePolicy redisFailurePolicy;
    private final LoginLogMapper loginLogMapper;
    private final HttpServletRequest httpServletRequest;

    /**
     * 发送短信验证码，开发环境返回 mockCode 明文
     */
    public Map<String, String> sendSmsCode(SendSmsCodeRequest request, String requestIp) {
        // 频率限制：同手机号 60s 内只能发 1 次
        String phoneKey = "sms:phone:" + request.phone();
        String ipKey = "sms:ip:" + requestIp;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(phoneKey))) {
                throw new IllegalArgumentException("验证码已发送，请 60 秒后再试");
            }
            // 频率限制：同 IP 每天最多 10 次
            String ipCount = redisTemplate.opsForValue().get(ipKey);
            if (ipCount != null && Integer.parseInt(ipCount) >= 10) {
                throw new IllegalArgumentException("今日验证码发送次数已达上限");
            }
        } catch (IllegalArgumentException e) {
            throw e; // Business rejection — re-throw
        } catch (Exception e) {
            redisFailurePolicy.onSecurityReadFailure("SMS rate limit check", phoneKey + "/" + ipKey, e);
        }

        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));

        // 记录频率（best-effort, Redis 不可用时跳过）
        try {
            redisTemplate.opsForValue().set(phoneKey, "1", Duration.ofSeconds(60));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("SMS phone rate set", phoneKey, e);
        }
        try {
            redisTemplate.opsForValue().increment(ipKey);
            redisTemplate.expire(ipKey, Duration.ofDays(1));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("SMS IP rate set", ipKey, e);
        }
        SmsCodeLog log = new SmsCodeLog();
        log.setPhone(request.phone());
        log.setBizType(request.bizType());
        log.setCodeHash(md5(code));
        log.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        log.setStatus(0);
        log.setRequestIp(requestIp);
        smsCodeLogMapper.insert(log);
        return Map.of("phone", request.phone(), "bizType", request.bizType(), "mockCode", code);
    }

    /**
     * 手机验证码注册，需设置密码
     */
    public LoginUserVO registerByPhoneCode(PhoneCodeAuthRequest request) {
        verifyCode(request.phone(), "REGISTER", request.code());
        if (request.password() == null || request.password().isBlank()
                || request.confirmPassword() == null || request.confirmPassword().isBlank()) {
            throw new IllegalArgumentException("请设置密码");
        }
        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次密码不一致");
        }
        if (request.password().length() < 6 || request.password().length() > 32) {
            throw new IllegalArgumentException("密码长度需要 6-32 位");
        }
        SysUser exists = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, request.phone())
                .last("limit 1"));
        if (exists != null) {
            throw new IllegalArgumentException("手机号已注册");
        }
        SysUser user = new SysUser();
        user.setPhone(request.phone());
        user.setUsername("u" + request.phone().substring(3));
        user.setPassword(new BCryptPasswordEncoder().encode(request.password()));
        user.setNickname("用户" + request.phone().substring(7));
        user.setStatus(1);
        user.setDeleted(0);
        sysUserMapper.insert(user);

        SysRole userRole = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, "USER")
                .eq(SysRole::getDeleted, 0)
                .last("limit 1"));
        if (userRole != null) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(user.getId());
            relation.setRoleId(userRole.getId());
            sysUserRoleMapper.insert(relation);
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        writeLoginLog(user, true, null, "phone");
        return new LoginUserVO(user.getId(), user.getPhone(), user.getUsername(), user.getNickname(), null, null, token);
    }

    /**
     * 手机验证码登录，更新最后登录时间
     */
    public LoginUserVO loginByPhoneCode(PhoneCodeAuthRequest request) {
        // 登录频率限制：每 IP 每分钟最多 5 次
        String loginRateKey = "rate:login:ip:" + httpServletRequest.getRemoteAddr();
        try {
            String count = redisTemplate.opsForValue().get(loginRateKey);
            if (count != null && Integer.parseInt(count) >= 5) {
                throw new IllegalArgumentException("登录尝试过于频繁，请稍后再试");
            }
        } catch (IllegalArgumentException e) {
            throw e; // Business rejection — re-throw
        } catch (Exception e) {
            redisFailurePolicy.onSecurityReadFailure("login rate check", loginRateKey, e);
        }
        // Record rate limit (best-effort)
        try {
            redisTemplate.opsForValue().increment(loginRateKey);
            redisTemplate.expire(loginRateKey, Duration.ofMinutes(1));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("login rate set", loginRateKey, e);
        }

        verifyCode(request.phone(), "LOGIN", request.code());
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, request.phone())
                .eq(SysUser::getDeleted, 0)
                .last("limit 1"));
        if (user == null) {
            writeLoginLog(null, false, "用户不存在", "phone");
            throw new IllegalArgumentException("用户不存在，请先注册");
        }
        user.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        writeLoginLog(user, true, null, "phone");
        return new LoginUserVO(user.getId(), user.getPhone(), user.getUsername(), user.getNickname(), user.getEmail(), user.getAvatar(), token);
    }

    /**
     * 密码登录
     */
    public LoginUserVO loginByPassword(PasswordLoginRequest request) {
        // 登录频率限制
        String loginRateKey = "rate:login:ip:" + httpServletRequest.getRemoteAddr();
        try {
            String count = redisTemplate.opsForValue().get(loginRateKey);
            if (count != null && Integer.parseInt(count) >= 5) {
                throw new IllegalArgumentException("登录尝试过于频繁，请稍后再试");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            redisFailurePolicy.onSecurityReadFailure("login rate check", loginRateKey, e);
        }
        try {
            redisTemplate.opsForValue().increment(loginRateKey);
            redisTemplate.expire(loginRateKey, Duration.ofMinutes(1));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("login rate set", loginRateKey, e);
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, request.phone())
                .eq(SysUser::getDeleted, 0)
                .last("limit 1"));
        if (user == null) {
            writeLoginLog(null, false, "用户不存在", "password");
            throw new IllegalArgumentException("用户不存在，请先注册");
        }

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String stored = user.getPassword();
        boolean matched;
        if (stored != null && stored.startsWith("$2")) {
            matched = encoder.matches(request.password(), stored);
        } else {
            matched = stored != null && stored.equals(md5(request.password()));
            if (matched) {
                user.setPassword(encoder.encode(request.password()));
            }
        }
        if (!matched) {
            writeLoginLog(user, false, "密码错误", "password");
            throw new IllegalArgumentException("密码错误");
        }

        user.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        writeLoginLog(user, true, null, "password");
        return new LoginUserVO(user.getId(), user.getPhone(), user.getUsername(), user.getNickname(), user.getEmail(), user.getAvatar(), token);
    }

    /**
     * 忘记密码 — 验证码校验后重置密码
     */
    public void resetPassword(ResetPasswordRequest request) {
        verifyCode(request.phone(), "RESET_PASSWORD", request.code());
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, request.phone())
                .eq(SysUser::getDeleted, 0)
                .last("limit 1"));
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setPassword(new BCryptPasswordEncoder().encode(request.newPassword()));
        sysUserMapper.updateById(user);
    }

    /**
     * 获取当前登录用户信息
     */
    public LoginUserVO currentUser() {
        Long loginId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(loginId);
        if (user == null || user.getDeleted() == 1) {
            throw new IllegalArgumentException("用户不存在");
        }
        return new LoginUserVO(user.getId(), user.getPhone(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getAvatar(), StpUtil.getTokenValue());
    }

    /**
     * 更新个人信息（昵称 / 邮箱 / 头像）
     */
    public LoginUserVO updateProfile(Long userId, String nickname, String email, String avatar) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getDeleted() == 1) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (nickname != null) user.setNickname(nickname);
        if (email != null) user.setEmail(email);
        if (avatar != null) user.setAvatar(avatar);
        sysUserMapper.updateById(user);
        return new LoginUserVO(user.getId(), user.getPhone(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getAvatar(), StpUtil.getTokenValue());
    }

    /**
     * 修改密码
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getDeleted() == 1) {
            throw new IllegalArgumentException("用户不存在");
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String stored = user.getPassword();
        boolean matched = false;
        // 优先 BCrypt，匹配失败降级 MD5（兼容老用户），同时自动升级为 BCrypt
        if (stored.startsWith("$2")) {
            matched = encoder.matches(oldPassword, stored);
        } else {
            matched = stored.equals(md5(oldPassword));
        }
        if (!matched) throw new IllegalArgumentException("原密码错误");
        user.setPassword(encoder.encode(newPassword));
        sysUserMapper.updateById(user);
    }

    private void writeLoginLog(SysUser user, boolean success, String failReason, String loginType) {
        try {
            LoginLog log = new LoginLog();
            log.setUserId(user != null ? user.getId() : null);
            log.setUsername(user != null ? user.getUsername() : null);
            log.setLoginStatus(success ? 1 : 0);
            log.setLoginType(loginType != null ? loginType : "phone");
            log.setIp(httpServletRequest.getRemoteAddr());
            log.setUserAgent(httpServletRequest.getHeader("User-Agent"));
            log.setFailReason(failReason);
            loginLogMapper.insert(log);
        } catch (Exception ignored) {}
    }

    /**
     * 退出登录
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 校验手机验证码有效性
     */
    private void verifyCode(String phone, String bizType, String code) {
        SmsCodeLog last = smsCodeLogMapper.selectOne(new LambdaQueryWrapper<SmsCodeLog>()
                .eq(SmsCodeLog::getPhone, phone)
                .eq(SmsCodeLog::getBizType, bizType)
                .eq(SmsCodeLog::getStatus, 0)
                .orderByDesc(SmsCodeLog::getId)
                .last("limit 1"));
        if (last == null) {
            throw new IllegalArgumentException("请先获取验证码");
        }
        if (last.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期");
        }
        if (!last.getCodeHash().equals(md5(code))) {
            throw new IllegalArgumentException("验证码错误");
        }
        last.setStatus(1);
        smsCodeLogMapper.updateById(last);
    }

    /**
     * MD5 哈希
     */
    private String md5(String source) {
        return DigestUtils.md5DigestAsHex(source.getBytes(StandardCharsets.UTF_8));
    }
}
