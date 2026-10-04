package com.blogsystem.music.infrastructure.cli;

import com.blogsystem.music.domain.model.*;
import com.blogsystem.music.domain.model.MusicAccount.*;
import com.blogsystem.music.domain.repository.MusicAccountRepository;
import com.blogsystem.music.infrastructure.configuration.MusicProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.blogsystem.music.domain.model.MusicException.Reason.*;

/**
 * Fixed CLI operations; no shell, browser credentials, audio downloads or raw diagnostics.
 */
@Repository
public class OfficialCliMusicRepository implements MusicAccountRepository {
    private final MusicProperties properties;
    private final ObjectMapper json;
    private final ReentrantLock operation = new ReentrantLock();
    private volatile Status cached;
    private volatile long checkedAt;

    public OfficialCliMusicRepository(MusicProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Status status() {
        if (!Files.isRegularFile(properties.state().resolve("home.json"))) return new Status(false, false);
        var value = cached;
        if (value != null && System.currentTimeMillis() - checkedAt < 8000) return value;
        value = execute("status", null, Status.class);
        cached = value;
        checkedAt = System.currentTimeMillis();
        return value;
    }

    @Override
    public Authorization authorize() {
        cached = null;
        return execute("authorize", null, Authorization.class);
    }

    @Override
    public Revocation revoke() {
        cached = null;
        return execute("logout", null, Revocation.class);
    }

    @Override
    public MusicPlaylist synchronize(String id) {
        return execute("sync", id, MusicPlaylist.class);
    }

    private <T> T execute(String action, String id, Class<T> type) {
        if (!operation.tryLock()) throw new MusicException(BUSY);
        Process process = null;
        try {
            var runner = properties.runtime().resolve("runner.cjs");
            if (!Files.isRegularFile(runner)) throw new MusicException(NOT_CONFIGURED);
            var args = new ArrayList<>(java.util.List.of(properties.getNode(), runner.toString(), properties.state().toString(), action));
            if (id != null) args.add(id);
            var builder = new ProcessBuilder(args).directory(properties.runtime().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Node wrapper receives no unrelated backend credentials.
            builder.environment().keySet().removeIf(k -> !java.util.Set.of("PATH", "Path", "SystemRoot", "WINDIR", "ComSpec", "TEMP", "TMP", "PATHEXT").contains(k));
            process = builder.start();
            Process running = process;
            var output = new CompletableFuture<byte[]>();
            var reader = new Thread(() -> {
                try {
                    byte[] bytes = running.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
                    if (bytes.length > 2 * 1024 * 1024) {
                        stop(running);
                        output.completeExceptionally(new IOException());
                    } else output.complete(bytes);
                } catch (IOException error) {
                    output.completeExceptionally(error);
                }
            }, "music-cli-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor("sync".equals(action) ? 180 : 55, TimeUnit.SECONDS))
                throw new MusicException(UNAVAILABLE);
            var result = json.readTree(new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            if (!result.path("ok").asBoolean(false)) {
                MusicException.Reason reason;
                try {
                    reason = MusicException.Reason.valueOf(result.path("reason").asText());
                } catch (IllegalArgumentException error) {
                    reason = UNAVAILABLE;
                }
                throw new MusicException(reason);
            }
            if (process.exitValue() != 0 || !result.has("data")) throw new MusicException(INVALID_RESPONSE);
            return json.treeToValue(result.get("data"), type);
        } catch (MusicException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MusicException(UNAVAILABLE);
        } catch (Exception error) {
            throw new MusicException(UNAVAILABLE);
        } finally {
            if (process != null && process.isAlive()) stop(process);
            operation.unlock();
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
