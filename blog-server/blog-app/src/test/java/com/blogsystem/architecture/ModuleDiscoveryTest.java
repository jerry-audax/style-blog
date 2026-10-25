package com.blogsystem.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleDiscoveryTest {
    @Test void everyProductionModuleHasImportedRepresentativeClasses() {
        var production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.blogsystem");
        for (String type : List.of("com.blogsystem.types.model.Identifier",
                "com.blogsystem.domain.asset.metadata.model.entity.MediaAsset",
                "com.blogsystem.application.asset.upload.service.MediaUploadApplicationService",
                "com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository",
                "com.blogsystem.trigger.http.asset.MediaUploadController", "com.blogsystem.BlogServerApplication")) {
            assertTrue(production.contain(type), type);
        }
        for (String layer : List.of("types", "domain", "application", "infrastructure", "trigger", "app")) {
            assertTrue(production.stream().anyMatch(type -> type.getPackageName().startsWith("com.blogsystem." + layer + ".")), layer);
        }
    }

    @Test void noLegacyContextFirstPackageRemains() {
        var production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.blogsystem");
        for (var type : production) {
            assertFalse(type.getPackageName().matches("com\\.blogsystem\\.(identity|content|interaction|asset|administration|ai|music|surprise|shared)(\\..*)?"), type.getName());
        }
    }
}
