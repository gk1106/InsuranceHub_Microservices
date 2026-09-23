package com.insurancehub.claims;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;

// api -> application -> domain; infrastructure implements ports. See CLAUDE.md package layout.
class LayeredArchitectureTest {

  private static final String BASE_PACKAGE = "com.insurancehub.claims";

  @Test
  void layersRespectDependencyDirection() {
    ArchRule rule =
        Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
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

    rule.check(new ClassFileImporter().importPackages(BASE_PACKAGE));
  }
}
