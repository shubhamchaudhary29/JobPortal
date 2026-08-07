package com.example.backend.shared.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

class ArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.example.backend");

    @Test
    void controllersNeverDependOnPersistenceInfrastructure() {
        noClasses().that().resideInAPackage("..api..").should()
                .dependOnClassesThat().resideInAPackage("..infrastructure..").check(classes);
    }

    @Test
    void featureCodeDoesNotDependOnControllersAndSharedDoesNotDependOnFeatureApis() {
        noClasses().that().resideOutsideOfPackage("..api..").should()
                .dependOnClassesThat().haveSimpleNameEndingWith("Controller").check(classes);
        noClasses().that().resideInAPackage("..shared..").should()
                .dependOnClassesThat().resideInAnyPackage("..auth.api..", "..user.api..", "..job.api..",
                        "..application.api..", "..messaging.api..").check(classes);
    }

    @Test
    void fieldInjectionIsForbidden() {
        noFields().should().beAnnotatedWith(Autowired.class).check(classes);
    }

    @Test
    void topLevelFeaturesAreFreeOfCycles() {
        slices().matching("com.example.backend.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void controllerSignaturesNeverExposePersistenceDocuments() {
        noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller").should()
                .haveRawReturnType(resideInAPackage("..infrastructure.."))
                .because("public controller responses must use API DTOs").check(classes);
    }
}
