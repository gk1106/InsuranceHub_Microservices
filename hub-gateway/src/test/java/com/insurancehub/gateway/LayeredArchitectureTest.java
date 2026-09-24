package com.insurancehub.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;

// api -> application -> domain; infrastructure implements ports. config/crypto/security/audit/
// mapping sit outside all four layers (like every other service's config/) and are unconstrained
// by these rules. See CLAUDE.md package layout and docs/adr/0003-ports-and-adapters.md.
class LayeredArchitectureTest {

  private static final String BASE_PACKAGE = "com.insurancehub.gateway";

  private static JavaClasses productionClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE);
  }

  @Test
  void layersRespectDependencyDirection() {
    ArchRule rule =
        Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api")
            .definedBy(BASE_PACKAGE + ".api..")
            .layer("Application")
            .definedBy(BASE_PACKAGE + ".application..")
            .layer("Domain")
            .definedBy(BASE_PACKAGE + ".domain..")
            .layer("Infrastructure")
            .definedBy(BASE_PACKAGE + ".infrastructure..")
            .whereLayer("Api")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
            .whereLayer("Infrastructure")
            .mayNotBeAccessedByAnyLayer();

    rule.check(productionClasses());
  }

  @Test
  void domainAndApplicationDoNotDependOnSpringData() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(BASE_PACKAGE + ".domain..", BASE_PACKAGE + ".application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data..");

    rule.check(productionClasses());
  }

  @Test
  void domainDoesNotDependOnApplication() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(BASE_PACKAGE + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(BASE_PACKAGE + ".application..");

    rule.check(productionClasses());
  }
}
