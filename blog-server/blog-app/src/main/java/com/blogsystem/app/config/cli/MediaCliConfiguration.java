package com.blogsystem.app.config.cli;

import com.blogsystem.application.asset.inventory.service.MediaInventoryApplicationService;
import com.blogsystem.application.asset.inventory.service.MediaInventoryDryRunApplicationService;
import com.blogsystem.application.asset.inventory.service.MediaInventoryReconciliationService;
import com.blogsystem.application.asset.inventory.service.MediaMetadataImportApplicationService;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.app.config.properties.MediaReferenceProperties;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackMetadataRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoMetadataRepository;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.blogsystem.infrastructure.filesystem.asset.LegacyMediaSnapshotReader;
import com.blogsystem.infrastructure.filesystem.asset.MediaMetadataImportInputReader;
import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaInventoryRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMetadataMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMetadataMapper;
import com.blogsystem.infrastructure.mybatis.schema.MybatisMediaImportSchemaRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Explicit offline composition, deliberately NOT a scanned Spring configuration/component. */
public final class MediaCliConfiguration {
    private MediaCliConfiguration() {}

    public static MediaMetadataImportApplicationService importer(
            Map<String, String> environment, String database) {
        String url = environment.get("MEDIA_IMPORT_DB_URL");
        if (!Set.of("blog_system", "blog_media_test").contains(database)
                || url == null
                || !url.matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/" + database))
            throw new IllegalArgumentException();
        var origins = new HashSet<URI>();
        String base = environment.get("IMGBED_BASE_URL");
        if (base == null || base.isBlank()) throw new IllegalArgumentException();
        origins.add(URI.create(base.strip()));
        String aliases = environment.getOrDefault("MEDIA_IMPORT_IMGBED_ALLOWED_ORIGINS", "");
        if (!aliases.isBlank()) {
            for (String alias : aliases.split(",", -1)) origins.add(URI.create(alias.strip()));
        }
        var limits = new MediaReferenceProperties();
        var links = new ImgBedReferenceLinkGateway(origins, "blog", limits.getMaxContentChars(), limits.getMaxLinks());
        var dataSource =
                new DriverManagerDataSource(
                        url,
                        environment.get("MEDIA_IMPORT_DB_USERNAME"),
                        environment.get("MEDIA_IMPORT_DB_PASSWORD"));
        try {
            var configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(MediaAssetMapper.class);
            configuration.addMapper(MusicTrackMetadataMapper.class);
            configuration.addMapper(SurpriseVideoMetadataMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            var session = new SqlSessionTemplate(factory.getObject());
            var transactions = new DataSourceTransactionManager(dataSource);
            transactions.setRollbackOnCommitFailure(true);
            var mapper = session.getMapper(MediaAssetMapper.class);
            var converter = new MediaAssetConverter();
            var assets = new MybatisMediaAssetRepository(mapper, converter);
            return new MediaMetadataImportApplicationService(
                    new MediaMetadataImportInputReader(json()),
                    new MybatisMediaImportSchemaRepository(dataSource),
                    assets,
                    new MybatisMusicTrackMetadataRepository(
                            session.getMapper(MusicTrackMetadataMapper.class)),
                    new MybatisSurpriseVideoMetadataRepository(
                            session.getMapper(SurpriseVideoMetadataMapper.class)),
                    MybatisMediaReferenceWriteRepository.forMetadataImport(
                            assets, mapper, converter, links, "cloudflare_imgbed", new MediaObjectKey("blog")),
                    new TransactionTemplate(transactions),
                    Clock.systemUTC());
        } catch (Exception failure) {
            throw new MediaImportFailure(
                    "MYBATIS_CONFIGURATION",
                    MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                    failure.getClass().getSimpleName());
        }
    }

    public static DryRunRuntime dryRun(Map<String, String> environment) {
        String token = environment.getOrDefault("IMGBED_API_TOKEN", "");
        if (token.isBlank()) throw new IllegalArgumentException();
        var settings =
                new ImgBedSettings(
                        environment.get("IMGBED_BASE_URL"),
                        token,
                        environment.getOrDefault("IMGBED_UPLOAD_CHANNEL", "telegram"),
                        environment.getOrDefault("IMGBED_CHANNEL_NAME", "TelegramBot"),
                        environment.getOrDefault("IMGBED_ROOT_FOLDER", "blog"),
                        Duration.ofSeconds(30));
        var json = json();
        var service =
                new MediaInventoryDryRunApplicationService(
                        new LegacyMediaSnapshotReader(json),
                        new MediaInventoryApplicationService(
                                new CloudflareMediaInventoryRepository(settings, json, true)),
                        new MediaInventoryReconciliationService(),
                        settings.rootFolder,
                        Clock.systemUTC());
        return new DryRunRuntime(
                service, "cloudflare_imgbed", settings.baseUrl, settings.rootFolder);
    }

    private static ObjectMapper json() {
        return new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record DryRunRuntime(
            MediaInventoryDryRunApplicationService service,
            String provider,
            URI endpoint,
            String root) {}
}
