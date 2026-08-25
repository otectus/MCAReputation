package dev.otectus.mcareputation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A standing tripwire against the Forge 1.20.1 idioms this port removed.
 *
 * <p>{@code OptionalClassloadTest} scans compiled bytecode, which catches a reference that actually
 * resolved. This scans <em>source</em>, which catches the other half: a fully-qualified name, a
 * commented-out block someone uncomments later, or an idiom that still compiles but means something
 * different now. It exists because "the port compiles" is not the same as "the port is finished" —
 * a file the migration skipped can compile fine right up until the moment it runs.
 *
 * <p>Comments are stripped before scanning, so documentation that explains why an idiom is gone does
 * not trip the rule that removed it. Matching is identifier-boundary aware, so {@code ForgeRegistries}
 * does not flag {@code NeoForgeRegistries}.
 */
class NeoForgePortLintTest {

    /** {@code {token, why it is banned}}. */
    private static final List<String[]> BANNED_IN_MAIN = List.of(
            new String[] {"net.minecraftforge", "the Forge package root; NeoForge is net.neoforged.*"},
            new String[] {"forge.net.mca", "MCA's Forgix-relocated root; 1.21.1 ships net.conczin.mca"},
            new String[] {"MinecraftForge.EVENT_BUS", "use NeoForge.EVENT_BUS"},
            new String[] {"new ResourceLocation(", "removed in 1.21; use fromNamespaceAndPath or parse"},
            new String[] {"ForgeRegistries", "use BuiltInRegistries"},
            new String[] {"ForgeConfigSpec", "use ModConfigSpec"},
            new String[] {"SimpleChannel", "use CustomPacketPayload and PayloadRegistrar"},
            new String[] {"NetworkRegistry", "use RegisterPayloadHandlersEvent"},
            new String[] {"NetworkEvent", "use IPayloadContext"},
            new String[] {"DistExecutor", "removed; ClientPacketHandler.Sink replaces it"},
            new String[] {"LivingHurtEvent", "use LivingDamageEvent.Post and getNewDamage()"},
            new String[] {"TickEvent.Phase", "use ServerTickEvent.Post / ClientTickEvent.Post"},
            new String[] {"@Mod.EventBusSubscriber", "EventBusSubscriber is a top-level annotation now"},
            new String[] {"ModLoadingContext", "config registers through the injected ModContainer"},
            new String[] {"FMLJavaModLoadingContext", "the mod bus is injected into the constructor"},
            new String[] {"ExtraCodecs.COMPONENT", "use ComponentSerialization.CODEC"},
            new String[] {"javax.annotation.Nullable", "use org.jetbrains.annotations.Nullable"},
            new String[] {"LazyOptional", "capabilities are gone; this mod uses SavedData anyway"},
            new String[] {"reobf", "ModDevGradle's jar output is already the distributable artifact"});

    /**
     * Test files that are themselves port tripwires, and therefore name banned tokens on purpose.
     * Kept as an explicit short list rather than a pattern so adding one is a deliberate act.
     */
    private static final java.util.Set<String> LINT_FILES = java.util.Set.of(
            "NeoForgePortLintTest.java",
            "OptionalClassloadTest.java");

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** True when {@code token} appears outside a longer identifier (so NeoForgeRegistries is safe). */
    private static boolean containsToken(String code, String token) {
        int from = 0;
        while (true) {
            int at = code.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            char before = at == 0 ? ' ' : code.charAt(at - 1);
            if (!Character.isJavaIdentifierPart(before) && before != '.') {
                return true;
            }
            from = at + 1;
        }
    }

    @Test
    void noMainSourceFileUsesAForgeEraIdiom() throws IOException {
        Path root = TestPaths.mainSourceRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String[] rule : BANNED_IN_MAIN) {
                    if (containsToken(code, rule[0])) {
                        offenders.add(root.relativize(file) + ": '" + rule[0] + "' — " + rule[1]);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "Forge-era idioms survive the port:\n  "
                + String.join("\n  ", offenders));
    }

    /** The same rules apply to tests: a test written against the old API proves nothing. */
    @Test
    void noTestSourceFileUsesAForgeEraIdiom() throws IOException {
        Path root = TestPaths.projectRoot().resolve("src/test/java/dev/otectus/mcareputation");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                // The port tripwires necessarily spell the banned tokens out — in a banned-token
                // table here, and in an assertion that the token is absent over in the classload
                // test. Naming a thing in order to forbid it is the rule, not a breach of it.
                if (LINT_FILES.contains(file.getFileName().toString())) {
                    continue;
                }
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String[] rule : BANNED_IN_MAIN) {
                    if (containsToken(code, rule[0])) {
                        offenders.add(root.relativize(file) + ": '" + rule[0] + "' — " + rule[1]);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "Forge-era idioms survive in the tests:\n  "
                + String.join("\n  ", offenders));
    }

    /** The build must not carry the ForgeGradle plugin, its repositories, or its reobf step. */
    @Test
    void theBuildScriptIsFullyOnModDevGradle() throws IOException {
        // Comments stripped for the same reason as the source scan: build.gradle explains why the
        // deobf pipeline is gone, and saying so must not read as still having one. Groovy shares
        // Java's comment syntax, so the same stripper works.
        String build = stripComments(Files.readString(
                TestPaths.projectRoot().resolve("build.gradle"), StandardCharsets.UTF_8));
        assertTrue(build.contains("net.neoforged.moddev"), "the ModDevGradle plugin must be applied");
        assertFalse(build.contains("net.minecraftforge.gradle"), "ForgeGradle must be gone");
        assertFalse(build.contains("fg.deobf"), "there is no deobf pipeline under NeoForge");
        assertFalse(build.contains("reobfJar"), "ModDevGradle's jar output is already distributable");
        assertFalse(build.contains("copyIdeResources"), "a ForgeGradle-only setting");

        String settings = stripComments(Files.readString(
                TestPaths.projectRoot().resolve("settings.gradle"), StandardCharsets.UTF_8));
        assertFalse(settings.contains("maven.minecraftforge.net"),
                "the Forge Maven is no longer a plugin repository for this project");
    }

    /**
     * The declared MCA range must stay wide enough to overlap both sibling mods.
     *
     * <p>MCA: Quests requires {@code mca [7.7,8)} and MCA: Conversations
     * {@code [7.7.36-beta.3,7.7.37)}. A range here that admitted only one of those would make the
     * three mods mutually uninstallable, which is the kind of thing nobody discovers until a modpack
     * refuses to launch. Note {@code [7.7.36,8)} is <em>not</em> wide enough: a Maven range excludes
     * prereleases below its lower bound, so it would reject 7.7.36-beta.3.
     */
    @Test
    void theMcaRangeStillOverlapsBothSiblings() throws IOException {
        String properties = Files.readString(TestPaths.projectRoot().resolve("gradle.properties"),
                StandardCharsets.UTF_8);
        assertTrue(properties.contains("mca_version_range=[7.7,8)"),
                "the MCA range must stay [7.7,8) so Reputation, Quests and Conversations can coexist");
    }
}
