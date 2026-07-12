package io.jethro.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Build-enforced module boundaries (ADR-0015). These rules are the thing that keeps
 * the monolith modular; weakening them "temporarily" is how extraction becomes a
 * rewrite. MODULE_ROOTS also pins the package naming convention — new modules
 * register here.
 */
class ModuleBoundariesTest {

    /** Top-level package per module (CLAUDE.md: package root io.jethro.<module>). */
    private static final List<String> MODULE_ROOTS = List.of(
            "io.jethro.trading.marketdata",
            "io.jethro.trading.algo",
            "io.jethro.trading.riskpnl",
            "io.jethro.trading.runtime",
            "io.jethro.order",
            "io.jethro.refdata",
            "io.jethro.uigateway",
            "io.jethro.finops");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.jethro");

    @Test
    void commonDomainIsDependencyFree() {
        // CLAUDE.md: no framework types in domain code — JDK and itself only.
        classes().that().resideInAPackage("io.jethro.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("io.jethro.domain..", "java..")
                .check(CLASSES);
    }

    @Test
    void messagingDependsOnlyOnAvroAndJdk() {
        // Schemas are contracts (invariant 6): no module logic leaks into messaging.
        classes().that().resideInAPackage("io.jethro.messaging..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("io.jethro.messaging..", "org.apache.avro..", "java..")
                .check(CLASSES);
    }

    @Test
    void internalPackagesAreModulePrivate() {
        // <module>.internal.. may only be accessed from within that module's subtree.
        for (String root : MODULE_ROOTS) {
            classes().that().resideInAPackage(root + ".internal..")
                    .should().onlyBeAccessed().byClassesThat().resideInAPackage(root + "..")
                    .allowEmptyShould(true)
                    .check(CLASSES);
        }
    }

    @Test
    void modulesDoNotDependOnEachOthersInternals() {
        // Cross-module coupling is allowed only via published interfaces or topics —
        // never another module's internal package.
        for (String root : MODULE_ROOTS) {
            noClasses().that().resideOutsideOfPackage(root + "..")
                    .should().dependOnClassesThat().resideInAPackage(root + ".internal..")
                    .allowEmptyShould(true)
                    .check(CLASSES);
        }
    }

    @Test
    void nothingDependsOnTheAppAssembly() {
        // The assembly wires modules; modules must never know about the assembly.
        noClasses().that().resideOutsideOfPackage("io.jethro.app..")
                .should().dependOnClassesThat()
                .resideInAPackage("io.jethro.app..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
