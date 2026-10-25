package com.blogsystem.app.config.asset;

import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.upload.result.MediaUploadLimits;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.infrastructure.mybatis.mapper.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaDeletionSchemaVerifier;
import com.blogsystem.trigger.http.asset.MediaCatalogCapabilitiesController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionOperations;
import java.util.Optional;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaDeletionRuntimeConfigurationTest {
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(MediaDeletionRuntimeConfiguration.class)
        .withBean(DataSource.class,()->mock(DataSource.class)).withBean(MediaDeletionSchemaVerifier.class,()->mock(MediaDeletionSchemaVerifier.class))
        .withBean(MediaDeletionAssetMapper.class,()->mock(MediaDeletionAssetMapper.class)).withBean(MediaDeletionOperationMapper.class,()->mock(MediaDeletionOperationMapper.class))
        .withBean(MusicTrackMapper.class,()->mock(MusicTrackMapper.class)).withBean(SurpriseVideoMapper.class,()->mock(SurpriseVideoMapper.class))
        .withBean(MediaReferenceRepository.class,()->mock(MediaReferenceRepository.class))
        .withBean("mediaCatalogTransactions",TransactionOperations.class,()->mock(TransactionOperations.class));}
    @Test void defaultOffDoesNotAccessNewSchemaAndLegacyDoesNotOfferProtocol(){
        runner().run(ctx->assertThat(ctx).hasNotFailed().doesNotHaveBean(MediaDeletionApplicationService.class));
        runner().withPropertyValues("media.catalog.storage=postgres").run(ctx->{assertThat(ctx).hasNotFailed();assertThat(ctx.getBean(MediaDeletionApplicationService.class).submissionEnabled()).isFalse();verifyNoInteractions(ctx.getBean(MediaDeletionSchemaVerifier.class));});
    }
    @Test void enabledRequiresEnvironmentAndCompatibleSchema(){
        runner().withPropertyValues("media.catalog.storage=postgres","media.catalog.deletions.enabled=true").run(ctx->assertThat(ctx).hasFailed());
        runner().withPropertyValues("media.catalog.storage=postgres","media.catalog.deletions.enabled=true","media.catalog.deletions.environment-id=local")
            .withBean(MediaDeletionSchemaVerifier.class,()->{var v=mock(MediaDeletionSchemaVerifier.class);doThrow(new IllegalStateException("unavailable")).when(v).verify();return v;})
            .run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void physicalDeleteIsRejectedEvenWhenSubmissionDisabled(){
        runner().withPropertyValues("media.catalog.deletions.physical-delete.enabled=true").run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void uploadAndDeleteSwitchesAreIndependentAndCapabilitiesPreserveUploadProtocol(){
        for(boolean uploads:new boolean[]{false,true})for(boolean deletes:new boolean[]{false,true}){
            runner().withPropertyValues("media.catalog.storage=postgres","media.catalog.uploads.enabled="+uploads,"media.catalog.deletions.enabled="+deletes,"media.catalog.deletions.environment-id=local")
                .run(ctx->{assertThat(ctx).hasNotFailed();var verifier=ctx.getBean(MediaDeletionSchemaVerifier.class);when(verifier.available()).thenReturn(true);
                    var base=new MediaCatalogCapabilities(true,true,uploads,uploads,false,false,uploads?"tracked-v1":null,uploads?new MediaUploadLimits(104857600,10485760,524288,209715200,16000000):null);
                    var controller=new MediaCatalogCapabilitiesController(base,Optional.of(ctx.getBean(MediaDeletionApplicationService.class)));
                    var result=controller.capabilities().getBody().data();assertThat(result.deleteProtocol()).isEqualTo("tracked-delete-v1");assertThat(result.catalogDelete()).isEqualTo(deletes);assertThat(result.mediaDelete()).isEqualTo(deletes);assertThat(result.mediaUpload()).isEqualTo(uploads);assertThat(result.uploadProtocol()).isEqualTo(base.uploadProtocol());
                });
        }
    }
}
