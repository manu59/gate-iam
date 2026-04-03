package fr.gate.iam.infrastructure.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "fr.gate.iam")
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule no_spring_in_domain =
            noClasses()
                    .that().resideInAPackage("fr.gate.iam.domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_jpa_in_domain =
            noClasses()
                    .that().resideInAPackage("fr.gate.iam.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.persistence..", "javax.persistence..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_infra_in_application =
            noClasses()
                    .that().resideInAPackage("fr.gate.iam.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("fr.gate.iam.infrastructure..")
                    .allowEmptyShould(true);
}
