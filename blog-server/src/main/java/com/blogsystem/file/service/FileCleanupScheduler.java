package com.blogsystem.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.content.entity.Article;
import com.blogsystem.content.mapper.ArticleMapper;
import com.blogsystem.file.entity.FileRecord;
import com.blogsystem.file.mapper.FileRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每天凌晨 3 点清理孤立文件：
 * 扫描超过 24 小时的 file_record，逐一检查是否被任意文章的 content_md 或 content_html 引用，
 * 未被引用的才删除文件 + 记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileCleanupScheduler {

    private final FileRecordMapper fileRecordMapper;
    private final ArticleMapper articleMapper;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanOrphanFiles() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<FileRecord> candidates = fileRecordMapper.selectList(
                new LambdaQueryWrapper<FileRecord>()
                        .lt(FileRecord::getCreatedAt, cutoff)
                        .last("limit 500"));
        if (candidates.isEmpty()) return;

        int deleted = 0;
        for (FileRecord f : candidates) {
            // file_url 现在统一存储相对路径，只需匹配相对路径即可
            final String relPath = f.getFileUrl();
            if (relPath == null) continue;
            boolean referenced = articleMapper.exists(new LambdaQueryWrapper<Article>()
                    .eq(Article::getDeleted, 0)
                    .and(w -> w.like(Article::getContentMd, "%" + relPath + "%")
                              .or().like(Article::getContentHtml, "%" + relPath + "%")));
            if (referenced) continue;

            try {
                Files.deleteIfExists(Path.of(f.getFilePath()));
                fileRecordMapper.deleteById(f.getId());
                deleted++;
            } catch (IOException e) {
                log.warn("删除孤儿文件失败: {}", f.getFilePath());
            }
        }
        if (deleted > 0) log.info("清理了 {} 个孤立文件", deleted);
    }
}
