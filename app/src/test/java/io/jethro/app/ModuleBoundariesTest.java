package io.jethro.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void migrationVersionsAreUniqueAcrossModules() throws IOException {
        // PersistenceConfig runs ONE Flyway over classpath:db/migration, merging every
        // module's migrations into a single line — so a version number is a GLOBAL
        // identifier, not a per-module one. Two modules picking the same number makes
        // Flyway refuse to resolve at all ("Found more than one migration with version
        // N") and every DB-backed bean fails, so the app does not boot. Nothing else
        // catches this: each module's migrations are internally consistent and the
        // collision only exists once the classpaths are merged at assembly.
        Map<String, List<String>> byVersion = new TreeMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql")) {
            String filename = Objects.requireNonNull(resource.getFilename());
            byVersion.computeIfAbsent(filename.substring(1, filename.indexOf("__")),
                    v -> new ArrayList<>()).add(filename);
        }

        // Guard against a vacuous pass: if the scan finds nothing, the assertion below
        // is meaningless and the rule silently stops protecting anything.
        assertThat(byVersion)
                .as("no migrations found on the classpath — the uniqueness check would be vacuous")
                .isNotEmpty();

        assertThat(byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1).toList())
                .as("Flyway migration versions must be unique across ALL modules "
                        + "(one Flyway run merges classpath:db/migration); "
                        + "renumber the newer file to the next free version")
                .isEmpty();
    }
}
