package com.blogsystem.surprise.infrastructure.persistence;

import com.blogsystem.surprise.domain.model.SurpriseVideo;
import com.blogsystem.surprise.domain.repository.SurpriseVideoRepository;
import com.blogsystem.surprise.infrastructure.configuration.SurpriseProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class JsonSurpriseVideoRepository implements SurpriseVideoRepository {
    private final ObjectMapper mapper;
    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonSurpriseVideoRepository(ObjectMapper mapper, SurpriseProperties properties) {
        this.mapper = mapper;
        this.file = properties.state().resolve("surprise-videos.json").normalize();
    }

    @Override public List<SurpriseVideo> findAll() { lock.readLock().lock(); try { return read(); } finally { lock.readLock().unlock(); } }
    @Override public Optional<SurpriseVideo> findById(String id) { return findAll().stream().filter(v -> v.id().equals(id)).findFirst(); }
    @Override public SurpriseVideo save(SurpriseVideo video) { lock.writeLock().lock(); try { List<SurpriseVideo> all = read(); all.removeIf(v -> v.id().equals(video.id())); all.add(video); write(all); return video; } finally { lock.writeLock().unlock(); } }
    @Override public void delete(String id) { lock.writeLock().lock(); try { List<SurpriseVideo> all = read(); all.removeIf(v -> v.id().equals(id)); write(all); } finally { lock.writeLock().unlock(); } }

    private List<SurpriseVideo> read() { if (!Files.exists(file)) return new ArrayList<>(); try { return new ArrayList<>(mapper.readValue(Files.readString(file), new TypeReference<>() { })); } catch (IOException e) { throw new IllegalStateException("惊喜视频目录读取失败", e); } }
    private void write(List<SurpriseVideo> all) { try { Files.createDirectories(file.getParent()); Path temp = Files.createTempFile(file.getParent(), "surprise-videos-", ".tmp"); Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardOpenOption.TRUNCATE_EXISTING); try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } } catch (IOException e) { throw new IllegalStateException("惊喜视频目录保存失败", e); } }
}
