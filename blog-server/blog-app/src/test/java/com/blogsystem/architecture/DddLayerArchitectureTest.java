package com.blogsystem.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

class DddLayerArchitectureTest {
    private static JavaClasses production;

    @BeforeAll static void importAllProductionModules() {
        production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.blogsystem");
        for (String layer : List.of("types", "domain", "application", "infrastructure", "trigger", "app")) {
            assertTrue(production.stream().anyMatch(type -> type.getPackageName().startsWith("com.blogsystem." + layer + ".")), layer);
        }
        assertFalse(production.contain("com.blogsystem.domain.archfixture.MisplacedDomainFixture"));
    }

    private static ArchRule domainRule() {
        return noClasses().that().resideInAPackage("com.blogsystem.domain..").should().dependOnClassesThat().resideInAnyPackage(
                "com.blogsystem.application..", "com.blogsystem.infrastructure..", "com.blogsystem.trigger..", "com.blogsystem.app..",
                "org.springframework..", "org.apache.ibatis..", "com.baomidou..", "com.fasterxml.jackson..", "reactor..",
                "jakarta.servlet..", "cn.dev33..", "java.sql..", "javax.sql..", "java.net.http..");
    }

    private static ArchRule triggerRule() {
        return noClasses().that().resideInAPackage("com.blogsystem.trigger..").should().dependOnClassesThat().resideInAnyPackage(
                "com.blogsystem.infrastructure..", "com.blogsystem.app..", "org.apache.ibatis..", "com.baomidou..", "java.sql..", "javax.sql..",
                "org.springframework.jdbc..", "org.springframework.data.redis..", "org.springframework.ai..",
                "org.springframework.web.reactive.function.client..", "java.net.http..");
    }

    @Test void typesDependOnlyOnPrimitives() {
        noClasses().that().resideInAPackage("com.blogsystem.types..").should().dependOnClassesThat().resideInAnyPackage(
                "com.blogsystem.domain..", "com.blogsystem.application..", "com.blogsystem.infrastructure..", "com.blogsystem.trigger..",
                "com.blogsystem.app..", "org.springframework..", "com.fasterxml.jackson..", "com.baomidou..").check(production);
    }

    @Test void domainOwnsPolicyWithoutTechnicalDependencies() { domainRule().check(production); }

    @Test void applicationCoordinatesOnlyInwardPorts() {
        noClasses().that().resideInAPackage("com.blogsystem.application..").should().dependOnClassesThat().resideInAnyPackage(
                "com.blogsystem.infrastructure..", "com.blogsystem.trigger..", "com.blogsystem.app..", "org.apache.ibatis..", "com.baomidou..",
                "org.springframework.jdbc..", "java.sql..", "javax.sql..", "org.springframework.data.redis..", "org.springframework.web..",
                "jakarta.servlet..", "org.springframework.ai..", "com.fasterxml.jackson..", "reactor..", "java.net.http..").check(production);
    }

    @Test void infrastructureCannotReachUseCasesOrEntryPoints() {
        noClasses().that().resideInAPackage("com.blogsystem.infrastructure..").should().dependOnClassesThat()
                .resideInAnyPackage("com.blogsystem.application..", "com.blogsystem.trigger..", "com.blogsystem.app..").check(production);
    }

    @Test void triggersContainNoPersistenceOrProviderClients() { triggerRule().check(production); }

    @Test void configurationIsOwnedByCompositionRoot() {
        classes().that().areMetaAnnotatedWith(Configuration.class).and().doNotHaveFullyQualifiedName("com.blogsystem.BlogServerApplication")
                .should().resideInAPackage("com.blogsystem.app.config..").check(production);
    }

    @Test void sqlMappersHaveTechnicalOwnership() {
        classes().that().areAnnotatedWith("org.apache.ibatis.annotations.Mapper").should()
                .resideInAPackage("com.blogsystem.infrastructure.mybatis.mapper..").check(production);
        assertTrue(production.stream().anyMatch(type -> type.getPackageName().startsWith("com.blogsystem.infrastructure.mybatis.mapper.")));
    }

    @Test void persistenceRecordsAndConvertersHaveDistinctPackages() {
        classes().that().resideInAPackage("com.blogsystem.infrastructure..").and().haveSimpleNameEndingWith("Record").should()
                .resideInAPackage("com.blogsystem.infrastructure.mybatis.record..").check(production);
        classes().that().resideInAPackage("com.blogsystem.infrastructure.mybatis..").and().haveSimpleNameEndingWith("Converter").should()
                .resideInAPackage("com.blogsystem.infrastructure.mybatis.converter..").check(production);
    }

    @Test void concreteDomainRepositoriesAreInfrastructureAdapters() {
        int implementations = 0;
        for (var type : production) {
            if (type.isInterface() || type.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)) continue;
            if (type.getAllRawInterfaces().stream().anyMatch(contract -> contract.getPackageName().startsWith("com.blogsystem.domain.")
                    && contract.getPackageName().contains(".repository"))) {
                implementations++;
                assertTrue(type.getPackageName().startsWith("com.blogsystem.infrastructure."), type.getName());
            }
        }
        assertTrue(implementations > 0);
    }

    @Test void crossContextReferenceBridgesRemainApplicationOrchestration() {
        for (String bridge : List.of("MusicMediaReferenceSource", "SurpriseMediaReferenceSource")) {
            assertTrue(production.contain("com.blogsystem.application.asset.inventory.service." + bridge), bridge);
        }
    }

    @Test void cliTriggersCannotStartProcessesOrReadEnvironment() {
        noClasses().that().resideInAPackage("com.blogsystem.trigger.cli..").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ProcessBuilder").check(production);
        noClasses().that().resideInAPackage("com.blogsystem.trigger.cli..").should().callMethod(System.class, "getenv", String.class).check(production);
    }

    @Test void aiContractsDoNotExposeReactiveOrProviderTypes() {
        noClasses().that().resideInAnyPackage("com.blogsystem.domain.ai..", "com.blogsystem.application.ai..")
                .should().dependOnClassesThat().resideInAnyPackage("reactor..", "org.springframework.ai..", "org.reactivestreams..").check(production);
    }

    @Test void onlySixLayerRootsAndStableMainRemain() {
        for (var type : production) {
            assertTrue(type.getName().equals("com.blogsystem.BlogServerApplication")
                    || type.getName().matches("com\\.blogsystem\\.(types|domain|application|infrastructure|trigger|app)\\..*"), type.getName());
            assertFalse(List.of("LocalAssetRepositoryAdapter", "FileController", "AssetCleanupScheduler").contains(type.getSimpleName()), type.getName());
        }
    }

    @Test void misplacedDomainFixtureActuallyFailsTheDomainRule() {
        var fixture = new ClassFileImporter().importClasses(com.blogsystem.domain.archfixture.MisplacedDomainFixture.class);
        assertThrows(AssertionError.class, () -> domainRule().check(fixture));
    }

    @Test void misplacedTriggerFixtureActuallyFailsTheTriggerRule() {
        var fixture = new ClassFileImporter().importClasses(com.blogsystem.trigger.archfixture.MisplacedTriggerFixture.class);
        assertThrows(AssertionError.class, () -> triggerRule().check(fixture));
    }
}
