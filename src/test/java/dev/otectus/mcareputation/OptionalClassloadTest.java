package dev.otectus.mcareputation;

import dev.otectus.mcareputation.compat.McaReflect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 13 and §36.5: the core jar must load with no companion mod present, on a dedicated
 * server, with no surviving reference to the platform it was ported off.
 *
 * <p>"Optional integration" is only true if the JVM starts with the optional jar absent (Appendix D).
 * The strongest cheap check is that no compiled class in this mod so much as <em>names</em> a
 * companion type — if none does, no classloader can be asked for one. This is a source-and-bytecode
 * assertion rather than a runtime one precisely because the runtime failure it guards against would
 * only show up on somebody else's machine.
 *
 * <p>Every path here goes through {@link TestPaths}. Under ModDevGradle the working directory is
 * {@code build/minecraft-junit}, and the earlier bare relative paths silently resolved to nothing —
 * which made two of these tests return early and report green while asserting nothing.
 */
class OptionalClassloadTest {

    /**
     * Package roots that must not appear in any compiled class's constant pool.
     *
     * <p>The last two are the port's own tripwires: {@code net/minecraftforge/} catches a file the
     * NeoForge migration missed, and {@code forge/net/mca/} catches a surviving reference to MCA's
     * old Forgix-relocated root, which 1.21.1 does not ship.
     */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "dev/otectus/mcaquests/",
            "dev/otectus/mcaconversations/",
            "dev/otectus/mcacrime/",
            "dev/architectury/",
            "me/shedaniel/",
            "dev/ftb/mods/",
            "net/minecraftforge/",
            "forge/net/mca/");

    @Test
    void noCompiledClassReferencesACompanionModOrTheOldPlatform() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TestPaths.compiledMainClasses())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                // The constant pool stores type names in internal form; a plain byte-level scan is
                // sufficient and needs no ASM dependency.
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN_PACKAGES) {
                    if (content.contains(forbidden)) {
                        offenders.add(file.getFileName() + " references " + forbidden);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "MCA: Reputation must name no optional mod and nothing "
                + "from the Forge platform:\n  " + String.join("\n  ", offenders));
    }

    /**
     * §14: MCA stays reflection-only, so <em>no</em> source file may import
     * {@code net.conczin.mca.*} - not even {@code compat}, which used to be the one exemption.
     * {@link McaReflect} resolves every MCA class by name, so an import anywhere would re-bind this
     * jar to a single MCA generation.
     */
    @Test
    void noSourceFileImportsMca() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path sourceRoot = TestPaths.mainSourceRoot();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("import net.conczin.mca.")) {
                    offenders.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "MCA must be reflection-only; imports found in:\n  "
                + String.join("\n  ", offenders));
    }

    /** §14.1: exactly one supported package root, and it is the unrelocated 1.21.1 one. */
    @Test
    void theOnlySupportedMcaRootIsTheUnrelocatedOne() {
        assertEquals(List.of("net.conczin.mca"), McaReflect.SUPPORTED_ROOTS,
                "the 1.20.1 Forgix roots cannot satisfy a 1.21.1 MCA and must not be probed");
    }

    /** §36.5: a dedicated server must never be handed a client class by mod initialisation. */
    @Test
    void serverSideClassesDoNotReferenceTheClientPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path sourceRoot = TestPaths.mainSourceRoot();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/client/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("mcareputation.client.")) {
                    offenders.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "client references outside the client package:\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * §14.3: {@code McaScreenCompat} keeps its {@code compat} package to minimise churn, so it is
     * the one class outside {@code client} allowed to name {@code net.minecraft.client}. That
     * exemption is only safe while nothing but the client entry point can reach it.
     */
    @Test
    void onlyTheScreenBridgeNamesAClientTypeOutsideTheClientPackage() throws IOException {
        String screenBridge = "dev/otectus/mcareputation/compat/McaScreenCompat";
        List<String> offenders = new ArrayList<>();
        List<String> reachers = new ArrayList<>();
        Path classRoot = TestPaths.compiledMainClasses();
        try (Stream<Path> files = Files.walk(classRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = classRoot.relativize(file).toString().replace('\\', '/');
                String owner = name.substring(0, name.length() - ".class".length());
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                boolean inClientPackage = owner.startsWith("dev/otectus/mcareputation/client/");
                if (!inClientPackage && !owner.equals(screenBridge)
                        && content.contains("net/minecraft/client/")) {
                    offenders.add(owner);
                }
                // Nested and synthetic classes carry the outer name, so trim at the first '$'.
                String outer = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
                if (!outer.equals(screenBridge) && content.contains(screenBridge)
                        && !reachers.contains(outer)) {
                    reachers.add(outer);
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "only McaScreenCompat may name a client type outside "
                + "the client package:\n  " + String.join("\n  ", offenders));
        assertEquals(List.of("dev/otectus/mcareputation/client/ReputationClient"), reachers,
                "ReputationClient must stay the only route to the screen bridge, so a dedicated "
                        + "server never loads it");
    }

    /**
     * §36.5, the NeoForge form: common packet code must not resolve a client class.
     *
     * <p>The Forge build achieved this with {@code DistExecutor}'s doubly-nested lambda, which
     * NeoForge 1.21.1 removed. The replacement is {@code ClientPacketHandler.Sink}, an interface
     * expressed only in this mod's own payload records — so the guarantee is now checked directly:
     * neither the common network registration nor the dispatch seam may carry a
     * {@code net/minecraft/client/} constant, whatever idiom produced it.
     */
    @Test
    void commonNetworkCodeNamesNoClientClass() throws IOException {
        Path classes = TestPaths.compiledMainClasses().resolve("dev/otectus/mcareputation/network");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (content.contains("net/minecraft/client/")
                        || content.contains("dev/otectus/mcareputation/client/")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "common network classes must not name a client type; "
                + "the sink seam exists so they do not:\n  " + String.join("\n  ", offenders));
    }

    /** The sink interface is the seam, so its own signatures must stay client-free at the source. */
    @Test
    void theClientSinkIsExpressedOnlyInThisModsOwnTypes() throws IOException {
        Path file = TestPaths.mainSourceRoot().resolve("network/ClientPacketHandler.java");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        // Scan the code, not the prose. This class's whole job is to explain why it names no client
        // type, so its javadoc says "net.minecraft.client" more than once on purpose.
        String code = stripComments(source);
        assertFalse(code.contains("net.minecraft.client."),
                "ClientPacketHandler must not name a client type, imported or fully-qualified");
        assertFalse(code.contains("DistExecutor"),
                "DistExecutor does not exist on NeoForge 1.21.1; the installable sink replaces it");
        assertTrue(code.contains("interface Sink"), "the dispatch seam is the Sink interface");
        assertTrue(code.contains("install("), "the sink must have exactly one mutation point");
    }

    /** Removes block and line comments so a lint checks the code rather than the prose about it. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void modMetadataDeclaresEveryCompanionAsOptional() throws IOException {
        Path metadata = TestPaths.mainResources().resolve("META-INF/neoforge.mods.toml");
        assertTrue(Files.isRegularFile(metadata),
                "neoforge.mods.toml missing; NeoForge ignores the Forge-spelled mods.toml");
        assertFalse(Files.exists(TestPaths.mainResources().resolve("META-INF/mods.toml")),
                "the Forge-era mods.toml must not survive the port");

        String toml = Files.readString(metadata, StandardCharsets.UTF_8);
        assertTrue(toml.contains("modId=\"mcaquests\""), "mcaquests dependency entry missing");
        assertTrue(toml.contains("modId=\"mcaconversations\""), "mcaconversations dependency entry missing");
        assertTrue(toml.contains("modId=\"mcacrime\""), "mcacrime dependency entry missing");
        assertTrue(toml.contains("modId=\"mca\""), "the mandatory MCA dependency is missing");
        assertTrue(toml.contains("modId=\"neoforge\""),
                "the loader dependency must be neoforge, not forge");
        assertFalse(toml.contains("modId=\"forge\""), "the Forge loader entry must not survive");

        // NeoForge spells these type="required"/type="optional"; Forge's mandatory=true/false is gone.
        assertFalse(toml.contains("mandatory="), "mandatory= is the Forge spelling and is ignored");
        long required = toml.lines().filter(line -> line.trim().equals("type=\"required\"")).count();
        assertTrue(required == 3, "expected exactly 3 required dependencies "
                + "(neoforge, minecraft, mca), found " + required);
        long optional = toml.lines().filter(line -> line.trim().equals("type=\"optional\"")).count();
        assertTrue(optional == 3, "expected exactly 3 optional companions "
                + "(mcaquests, mcaconversations, mcacrime), found " + optional);

        assertTrue(toml.contains("# NOTE: Architectury is deliberately"),
                "the Architectury omission should stay documented, not silently dropped");
        assertTrue(toml.lines().noneMatch(line -> line.trim().startsWith("modId=\"architectury\"")),
                "Architectury must not be declared: MCA 1.21.1 dropped it and this mod never used it");
        assertTrue(toml.lines().noneMatch(line -> line.trim().startsWith("[[mixins]]")),
                "this mod ships no mixins");
    }

    /** NeoForge synthesises pack metadata for mods; a 1.20.1 pack_format would be stale and wrong. */
    @Test
    void noStalePackMetadataIsShipped() {
        assertFalse(Files.exists(TestPaths.mainResources().resolve("pack.mcmeta")),
                "pack.mcmeta is a Forge-era leftover; NeoForge generates pack metadata for mods");
    }
}
