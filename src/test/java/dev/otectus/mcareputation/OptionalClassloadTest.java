package dev.otectus.mcareputation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 13 and §36.5: the core jar must load with no companion mod present.
 *
 * <p>"Optional integration" is only true if the JVM starts with the optional jar absent (Appendix D).
 * The strongest cheap check is that no compiled class in this mod so much as <em>names</em> a
 * companion type — if none does, no classloader can be asked for one. This is a source-and-bytecode
 * assertion rather than a runtime one precisely because the runtime failure it guards against would
 * only show up on somebody else's machine.
 */
class OptionalClassloadTest {

    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "dev/otectus/mcaquests/",
            "dev/otectus/mcaconversations/",
            "dev/otectus/mcacrime/",
            "dev/architectury/",
            "me/shedaniel/",
            "dev/ftb/mods/");

    private static Path compiledClasses() {
        URL marker = OptionalClassloadTest.class.getResource("/dev/otectus/mcareputation/McaReputation.class");
        assertNotNull(marker, "compiled classes not found on the test classpath");
        Path classFile = Paths.get(marker.getPath().replaceFirst("^/([A-Za-z]:)", "$1"));
        return classFile.getParent().getParent().getParent().getParent(); // .../classes/java/main
    }

    @Test
    void noCompiledClassReferencesACompanionMod() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(compiledClasses())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                // The constant pool stores type names in internal form; a plain byte-level scan is
                // sufficient and needs no ASM dependency.
                String content = new String(bytes, StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN_PACKAGES) {
                    if (content.contains(forbidden)) {
                        offenders.add(file.getFileName() + " references " + forbidden);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "MCA: Reputation must not name any optional mod:\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * §11: only {@code compat} may import {@code forge.net.mca.*}. Everything else must go through it,
     * so an MCA signature change has exactly one place to be fixed.
     */
    @Test
    void onlyTheCompatPackageImportsMca() throws IOException {
        Path sourceRoot = Paths.get("src/main/java/dev/otectus/mcareputation");
        if (!Files.isDirectory(sourceRoot)) {
            return; // running from a packaged artifact rather than the source tree
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.toString().replace('\\', '/').contains("/compat/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("import forge.net.mca.")) {
                    offenders.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "MCA imports outside compat:\n  "
                + String.join("\n  ", offenders));
    }

    /** §36.5: a dedicated server must never be handed a client class by mod initialisation. */
    @Test
    void serverSideClassesDoNotReferenceTheClientPackage() throws IOException {
        Path sourceRoot = Paths.get("src/main/java/dev/otectus/mcareputation");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/client/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean namesClientPackage = source.contains("mcareputation.client.");
                boolean guarded = source.contains("DistExecutor");
                if (namesClientPackage && !guarded) {
                    offenders.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "unguarded client references outside the client package:\n  "
                + String.join("\n  ", offenders));
    }

    @Test
    void modsTomlDeclaresBothCompanionsAsOptional() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/mods.toml")) {
            assertNotNull(in, "mods.toml missing from the built resources");
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("modId=\"mcaquests\""), "mcaquests dependency entry missing");
            assertTrue(toml.contains("modId=\"mcaconversations\""), "mcaconversations dependency entry missing");
            assertTrue(toml.contains("modId=\"mca\""), "the mandatory MCA dependency is missing");
            // Exactly one mandatory=true block per hard dependency: forge, minecraft, mca.
            long mandatory = toml.lines().filter(line -> line.trim().equals("mandatory=true")).count();
            assertTrue(mandatory == 3, "expected exactly 3 mandatory dependencies, found " + mandatory);
            assertTrue(toml.contains("# NOTE: Architectury is deliberately"),
                    "the Architectury omission should stay documented, not silently dropped");
            assertTrue(toml.lines().noneMatch(line -> line.trim().startsWith("modId=\"architectury\"")),
                    "Architectury must not be declared: MCA 7.7 dropped it and this mod never used it");
            assertTrue(toml.lines().noneMatch(line -> line.trim().startsWith("[[mixins]]")),
                    "this mod ships no mixins");
        }
    }
}
