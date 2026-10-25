package com.blogsystem.it.media;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.identity.model.PhoneNumber;
import com.blogsystem.domain.identity.model.User;
import com.blogsystem.domain.identity.model.UserId;
import com.blogsystem.infrastructure.adapter.repository.identity.MybatisOwnerAccountRepository;
import com.blogsystem.infrastructure.adapter.repository.identity.MybatisUserRepository;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OwnerAvatarWriteProtectionIntegrationTest {
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000841");
    private static final UUID NEXT = UUID.fromString("80000000-0000-0000-0000-000000000842");
    private static final UUID THIRD = UUID.fromString("90000000-0000-0000-0000-000000000843");
    private static final String OLD_URL = "/file/blog/avatars/old.png";
    private static final String NEXT_URL = "https://img.example/file/blog/avatars/new.png";

    // Removing the guard would let even a nickname-only save retain a deleting avatar.
    @Test void nicknameOnlySaveProtectsRetainedAvatar() throws Exception {
        try (var f = activeOwner()) {
            var saved = f.base.tx.execute(s -> f.owners.updateProfile(1, "改名", null, null));
            assertEquals(OLD_URL, saved.avatar());
            f.base.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", OLD);
            conflict(() -> f.base.tx.execute(s -> f.owners.updateProfile(1, "不应写入", null, null)));
            conflict(() -> f.base.tx.execute(s -> f.owners.updateProfile(1, null, null, null)));
            assertEquals("改名", text(f, "nickname"));
            assertEquals(OLD_URL, text(f, "avatar"));
        }
    }

    @Test void avatarChangeLocksOldAndNewBeforeAccountRow() throws Exception {
        try (var f = activeOwner()) {
            f.base.insert(NEXT, "blog/avatars/new.png");
            f.base.trace.statements.clear();
            var ids = new ArrayList<UUID>();
            var repository = f.repository(f.mapper, observedGuard(f, ids));
            var saved = f.base.tx.execute(s -> repository.updateProfile(1, "新昵称", "new@example.test", NEXT_URL));
            assertEquals(NEXT_URL, saved.avatar());
            assertEquals("new@example.test", text(f, "email"));
            assertEquals("synthetic-original", text(f, "password"));
            assertEquals("fixture-owner", text(f, "username"));
            var sql = f.base.trace.statements;
            int assetLock = index(sql, "from media_asset", "for update");
            int ownerLock = index(sql, "from sys_user", "for update");
            assertTrue(assetLock >= 0 && ownerLock > assetLock, "resource locks must precede account row lock");
            assertEquals(List.of(OLD, NEXT), ids);
            assertEquals(NEXT_URL, f.base.tx.execute(s -> f.owners.updateProfile(1, null, null, null)).avatar());
        }
    }

    @Test void clearingUnregisteredOldAvatarIsAllowed() throws Exception {
        try (var f = new OwnerMediaWriteFixture()) {
            f.account("/file/blog/avatars/unregistered.png");
            conflict(() -> f.base.tx.execute(s -> f.owners.updateProfile(1, "不能保留", null, null)));
            var cleared = f.base.tx.execute(s -> f.owners.updateProfile(1, "已清空", null, ""));
            assertEquals("", cleared.avatar());
            assertEquals("", f.base.tx.execute(s -> f.owners.updateProfile(1, null, null, null)).avatar());
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM media_asset", Integer.class));
        }
    }

    @Test void failedProtectionKeepsWholeProfileUnchanged() throws Exception {
        try (var f = activeOwner()) {
            var before = f.base.jdbc.queryForMap("SELECT * FROM sys_user WHERE id=1");
            conflict(() -> f.base.tx.execute(s -> f.owners.updateProfile(1, "不应写入", "bad@example.test", "/file/blog/avatars/missing.png")));
            assertEquals(before, f.base.jdbc.queryForMap("SELECT * FROM sys_user WHERE id=1"));
        }
    }

    @Test @Timeout(30) void concurrentAvatarChangeRejectsWithoutUnorderedRelock() throws Exception {
        try (var f = activeOwner()) {
            f.base.insert(NEXT, "blog/avatars/new.png");
            f.base.insert(THIRD, "blog/avatars/third.png");
            var observed = new CountDownLatch(1);
            var changed = new CountDownLatch(1);
            var ids = new ArrayList<UUID>();
            var actual = observedGuard(f, ids);
            var delayed = f.repository(f.mapper, (previous, desired) -> {
                observed.countDown(); await(changed); actual.lockForWrite(previous, desired);
            });
            var pool = Executors.newSingleThreadExecutor();
            try {
                var first = pool.submit(() -> assertThrows(MediaReferenceWriteException.class,
                        () -> f.base.tx.execute(s -> delayed.updateProfile(1, "迟到资料", null, "/file/blog/avatars/third.png"))));
                assertTrue(observed.await(10, TimeUnit.SECONDS));
                f.base.tx.executeWithoutResult(s -> f.owners.updateProfile(1, "最新资料", null, NEXT_URL));
                changed.countDown();
                assertEquals(MediaReferenceWriteException.Reason.CONFLICT, first.get(10, TimeUnit.SECONDS).reason());
                assertEquals(List.of(OLD, THIRD), ids);
                assertEquals(NEXT_URL, text(f, "avatar"));
                assertEquals("最新资料", text(f, "nickname"));
            } finally { changed.countDown(); stop(pool); }
        }
    }

    @Test @Timeout(30) void nicknameOnlyEditCannotRestoreClearedAvatar() throws Exception {
        try (var f = activeOwner()) {
            var observed = new CountDownLatch(1);
            var changed = new CountDownLatch(1);
            var delayed = f.repository(f.mapper, (previous, desired) -> {
                observed.countDown(); await(changed); f.base.guard.lockForWrite(previous, desired);
            });
            var pool = Executors.newSingleThreadExecutor();
            try {
                var first = pool.submit(() -> assertThrows(MediaReferenceWriteException.class,
                        () -> f.base.tx.execute(s -> delayed.updateProfile(1, "迟到昵称", null, null))));
                assertTrue(observed.await(10, TimeUnit.SECONDS));
                f.base.tx.executeWithoutResult(s -> f.owners.updateProfile(1, "清空后的昵称", null, ""));
                changed.countDown();
                assertEquals(MediaReferenceWriteException.Reason.CONFLICT, first.get(10, TimeUnit.SECONDS).reason());
                assertEquals("", text(f, "avatar"));
                assertEquals("清空后的昵称", text(f, "nickname"));
            } finally { changed.countDown(); stop(pool); }
        }
    }

    enum TechnicalWrite { LOGIN, LOGIN_UPGRADE, PASSWORD, STATUS }

    // Pause after a real mapper read, commit a new profile, then let the old technical write finish.
    @ParameterizedTest @EnumSource(TechnicalWrite.class) @Timeout(30)
    void lateLoginPasswordAndStatusUpdatesCannotRestoreOldAvatar(TechnicalWrite kind) throws Exception {
        try (var f = activeOwner()) {
            f.base.insert(NEXT, "blog/avatars/new.png");
            var read = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var delayedMapper = afterFirstRead(f.mapper, read, resume);
            var delayedOwner = f.repository(delayedMapper, f.base.guard);
            var delayedUser = new MybatisUserRepository(delayedMapper);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var late = pool.submit(() -> f.base.tx.executeWithoutResult(s -> {
                    switch (kind) {
                        case LOGIN -> delayedOwner.recordSuccessfulLogin(1, null);
                        case LOGIN_UPGRADE -> delayedOwner.recordSuccessfulLogin(1, "synthetic-upgrade");
                        case PASSWORD -> delayedOwner.changePassword(1, "synthetic-change");
                        case STATUS -> {
                            var user = User.register(new UserId(1L), new PhoneNumber("13900000000"), Instant.parse("2026-10-18T00:00:00Z"));
                            user.disable(Instant.parse("2026-10-18T01:00:00Z"));
                            delayedUser.save(user);
                        }
                    }
                }));
                assertTrue(read.await(10, TimeUnit.SECONDS));
                f.base.tx.executeWithoutResult(s -> f.owners.updateProfile(1, "最新昵称", "latest@example.test", NEXT_URL));
                resume.countDown(); late.get(10, TimeUnit.SECONDS);
                assertEquals(NEXT_URL, text(f, "avatar"));
                assertEquals("最新昵称", text(f, "nickname"));
                assertEquals("latest@example.test", text(f, "email"));
                assertEquals("fixture-owner", text(f, "username"));
                assertEquals(kind == TechnicalWrite.LOGIN_UPGRADE ? "synthetic-upgrade" : kind == TechnicalWrite.PASSWORD ? "synthetic-change" : "synthetic-original", text(f, "password"));
                assertEquals(kind == TechnicalWrite.STATUS ? 0 : 1, f.base.jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class));
                var lastLogin = f.base.jdbc.queryForObject("SELECT last_login_time FROM sys_user WHERE id=1", LocalDateTime.class);
                if (kind == TechnicalWrite.LOGIN || kind == TechnicalWrite.LOGIN_UPGRADE) assertNotNull(lastLogin);
                else assertNull(lastLogin);
                assertEquals(LocalDateTime.parse("2026-10-18T00:00:00"), f.base.jdbc.queryForObject("SELECT created_at FROM sys_user WHERE id=1", LocalDateTime.class));
                assertEquals(LocalDateTime.parse(kind == TechnicalWrite.STATUS ? "2026-10-18T01:00:00" : "2026-10-18T00:00:00"), f.base.jdbc.queryForObject("SELECT updated_at FROM sys_user WHERE id=1", LocalDateTime.class));
            } finally { resume.countDown(); stop(pool); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"status=0", "deleted=1"})
    void accountMadeUnavailableAfterSnapshotCannotBeEdited(String transition) throws Exception {
        try (var f = activeOwner()) {
            var repository = f.repository(f.mapper, (previous, desired) -> {
                f.base.guard.lockForWrite(previous, desired);
                f.base.jdbc.update("UPDATE sys_user SET " + transition + " WHERE id=1");
            });
            conflict(() -> f.base.tx.execute(s -> repository.updateProfile(1, "不能写入", null, null)));
            assertEquals("原昵称", text(f, "nickname"));
            assertEquals(1, f.base.jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT deleted FROM sys_user WHERE id=1", Integer.class));
        }
    }

    @Test void springRepositoryProxyProvidesProfileTransaction() throws Exception {
        try (var f = activeOwner(); var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            context.registerBean(DataSource.class, () -> f.base.dataSource);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(f.base.dataSource));
            context.registerBean(MybatisOwnerAccountRepository.class, () -> f.owners);
            context.refresh();
            assertEquals("代理保存", context.getBean(MybatisOwnerAccountRepository.class).updateProfile(1, "代理保存", null, null).nickname());
            assertEquals("代理保存", text(f, "nickname"));
        }
    }

    private static OwnerMediaWriteFixture activeOwner() throws Exception {
        var f = new OwnerMediaWriteFixture();
        try { f.base.insert(OLD, "blog/avatars/old.png"); f.account(OLD_URL); return f; }
        catch (Exception failure) { f.close(); throw failure; }
    }
    private static String text(OwnerMediaWriteFixture f, String column) {
        return f.base.jdbc.queryForObject("SELECT " + column + " FROM sys_user WHERE id=1", String.class);
    }
    private static int index(List<String> sql, String table, String lock) {
        for (int i = 0; i < sql.size(); i++) if (sql.get(i).contains(table) && sql.get(i).contains(lock)) return i;
        return -1;
    }
    private static void conflict(Runnable action) {
        assertEquals(MediaReferenceWriteException.Reason.CONFLICT, assertThrows(MediaReferenceWriteException.class, action::run).reason());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void stop(java.util.concurrent.ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
    private static OwnerAccountMapper afterFirstRead(OwnerAccountMapper actual, CountDownLatch read, CountDownLatch resume) {
        var first = new AtomicBoolean(true);
        return (OwnerAccountMapper) Proxy.newProxyInstance(OwnerAccountMapper.class.getClassLoader(), new Class<?>[]{OwnerAccountMapper.class}, (proxy, method, args) -> {
            try {
                var value = method.invoke(actual, args);
                if (method.getName().equals("selectById") && first.compareAndSet(true, false)) { read.countDown(); await(resume); }
                return value;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private static MediaReferenceWriteRepository observedGuard(OwnerMediaWriteFixture f, List<UUID> ids) {
        return f.base.guard(new MediaDeletionAssetRepository() {
            @Override public java.util.Optional<MediaDeletionAssetSnapshot> findById(UUID id) { return f.base.locks.findById(id); }
            @Override public List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> wanted) {
                var rows = f.base.locks.lockByIds(wanted); rows.forEach(row -> ids.add(row.asset().id())); return rows;
            }
            @Override public MediaDeletionAssetSnapshot confirmOwnership(UUID id, MediaDeletionOwnership ownership, long version, Instant now) { return f.base.locks.confirmOwnership(id, ownership, version, now); }
        });
    }
    @TestConfiguration @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {}
}
