package fr.gate.iam.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

class HexagonalArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .importPackages("fr.gate.iam");
    }

    @Test
    void domain_must_not_depend_on_spring() {
        noClasses()
                .that().resideInAPackage("fr.gate.iam.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence.."
                )
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domain_must_not_depend_on_application() {
        noClasses()
                .that().resideInAPackage("fr.gate.iam.domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("fr.gate.iam.application..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domain_must_not_depend_on_infrastructure() {
        noClasses()
                .that().resideInAPackage("fr.gate.iam.domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("fr.gate.iam.infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void application_must_not_depend_on_infrastructure() {
        noClasses()
                .that().resideInAPackage("fr.gate.iam.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("fr.gate.iam.infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void application_must_not_depend_on_spring_web() {
        noClasses()
                .that().resideInAPackage("fr.gate.iam.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void layered_architecture_is_respected() {
        layeredArchitecture()
                .consideringAllDependencies()
                .withOptionalLayers(true)
                .layer("Backend").definedBy("fr.gate.iam.backend..")
                .layer("Infrastructure").definedBy("fr.gate.iam.infrastructure..")
                .layer("Application").definedBy("fr.gate.iam.application..")
                .layer("Domain").definedBy("fr.gate.iam.domain..")
                .whereLayer("Domain").mayNotAccessAnyLayer()
                .whereLayer("Application").mayOnlyAccessLayers("Domain")
                .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain")
                .whereLayer("Backend").mayOnlyAccessLayers("Infrastructure", "Application", "Domain")
                .check(classes);
    }
}
