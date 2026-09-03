package dev.otectus.mcareputation.data;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import dev.otectus.mcareputation.TestPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec §16.3 #4: the metadata FML will actually read must say what the build intends.
 *
 * <p>This parses the {@code processResources} <em>output</em>, not the template in {@code src}. The
 * template is all {@code ${...}} placeholders, so a source-level check proves only that the
 * placeholders are spelled right — it cannot catch an expansion that never happened, a property
 * renamed on one side only, or a mojibaked description. It is also parsed with NightConfig, which is
 * the same library FML uses: a file that only <em>looks</em> like TOML fails here exactly as it would
 * at startup, where the symptom is the useless "not a valid mod file".
 *
 * <p>Every expected version and range is read back out of {@code gradle.properties}. Hard-coding one
 * here would put a version string in two places and guarantee they eventually disagree.
 */
class NeoForgeMetadataTest {

    /** The NeoForge metadata file. Its Forge-era spelling, {@code mods.toml}, must not exist. */
    private static final String METADATA = "META-INF/neoforge.mods.toml";

    private static CommentedConfig metadata() throws IOException {
        Path file = TestPaths.processedMainResources().resolve(METADATA);
        assertTrue(Files.isRegularFile(file), () -> "expected the processResources output at " + file
                + "; run `gradlew processResources` (the `test` task depends on it) before this test");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new TomlParser().parse(reader);
        }
    }

    private static Properties gradleProperties() throws IOException {
        Properties properties = new Properties();
        // ISO-8859-1: the Java Properties charset, and what Gradle itself reads this file with.
        try (Reader reader = Files.newBufferedReader(TestPaths.projectRoot().resolve("gradle.properties"),
                StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        return properties;
    }

    /** The one {@code [[mods]]} entry, as a config. */
    private static Config theMod(Config metadata) {
        List<Config> mods = metadata.get("mods");
        assertNotNull(mods, "the metadata declares no [[mods]] entry at all");
        assertEquals(1, mods.size(), "this jar ships exactly one mod");
        return mods.get(0);
    }

    /** The declared dependency entry for {@code modId}, or a failure naming what was found instead. */
    private static Config dependency(Config metadata, String modId) {
        List<Config> dependencies = metadata.get(List.of("dependencies", "mcareputation"));
        assertNotNull(dependencies, "the metadata declares no dependencies.mcareputation table");
        return dependencies.stream()
                .filter(entry -> modId.equals(entry.get("modId")))
                .findFirst()
                .orElseGet(() -> fail("no dependency entry for '" + modId + "'; declared: "
                        + dependencies.stream().map(entry -> (String) entry.get("modId")).toList()));
    }

    /** The loader declaration FML reads before anything else. */
    @Test
    void theLoaderDeclarationIsSane() throws IOException {
        CommentedConfig metadata = metadata();
        assertEquals("javafml", metadata.get("modLoader"),
                "this mod is plain Java; a different loader here would not load it at all");
        String loaderVersion = metadata.get("loaderVersion");
        assertEquals(gradleProperties().getProperty("loader_version_range"), loaderVersion,
                "loaderVersion must come from loader_version_range");
        assertNotNull(metadata.get("license"), "a missing license field fails FML's own validation");
    }

    /** The mod's own identity, including the version the build stamps into the jar manifest. */
    @Test
    void theModEntryMatchesGradleProperties() throws IOException {
        Properties properties = gradleProperties();
        Config mod = theMod(metadata());

        assertEquals("mcareputation", mod.get("modId"));
        assertEquals(properties.getProperty("mod_version"), mod.<String>get("version"),
                "the declared version must be the one processResources expanded; an unexpanded "
                        + "placeholder here means the mod loads reporting a literal ${mod_version}");
        assertEquals(properties.getProperty("mod_name"), mod.<String>get("displayName"));

        String description = mod.get("description");
        assertNotNull(description, "a mod with no description");
        assertTrue(description.chars().allMatch(c -> c < 0x80),
                "the description must stay pure ASCII: gradle.properties is read as ISO-8859-1, so a "
                        + "non-escaped UTF-8 character arrives mojibaked and NightConfig then rejects "
                        + "the whole file");
    }

    /** MCA is what this mod is <em>about</em>; without it there is nothing to have standing with. */
    @Test
    void mcaIsRequiredAndOrderedBefore() throws IOException {
        Config mca = dependency(metadata(), "mca");
        assertEquals("required", mca.get("type"), "MCA: Reputation is meaningless without MCA Reborn");
        assertEquals("AFTER", mca.get("ordering"),
                "loading after MCA is what makes its entities and village manager present");
        assertEquals(gradleProperties().getProperty("mca_version_range"), mca.<String>get("versionRange"),
                "the declared MCA range must be mca_version_range, not a second copy of it");
    }

    /** The platform bounds, which are the only thing stopping this jar loading on the wrong game. */
    @Test
    void thePlatformRangesComeFromGradleProperties() throws IOException {
        CommentedConfig metadata = metadata();
        Properties properties = gradleProperties();

        Config minecraft = dependency(metadata, "minecraft");
        assertEquals("required", minecraft.get("type"));
        assertEquals(properties.getProperty("minecraft_version_range"),
                minecraft.<String>get("versionRange"));

        Config neoforge = dependency(metadata, "neoforge");
        assertEquals("required", neoforge.get("type"));
        assertEquals(properties.getProperty("neoforge_version_range"),
                neoforge.<String>get("versionRange"));
    }

    /**
     * §36.1 group 13: every companion stays optional. A single {@code required} here would make an
     * install of Reputation alone refuse to start — the exact failure the whole compat seam exists to
     * prevent.
     */
    @Test
    void everyCompanionIsOptional() throws IOException {
        CommentedConfig metadata = metadata();
        for (String companion : List.of("mcaquests", "mcaconversations", "mcacrime")) {
            assertEquals("optional", dependency(metadata, companion).get("type"),
                    companion + " must never become a hard dependency");
        }
    }

    /**
     * §11: this mod ships no mixin. A {@code [[mixins]]} declaration naming a config that is not in
     * the jar is a hard startup crash, and one that <em>is</em> in the jar means a mixin got added
     * without anybody deciding to add one.
     */
    @Test
    void noMixinConfigIsDeclared() throws IOException {
        Object mixins = metadata().get("mixins");
        assertTrue(mixins == null || (mixins instanceof List<?> list && list.isEmpty()),
                () -> "a [[mixins]] declaration appeared: " + mixins);
    }

    /**
     * The Forge-era {@code META-INF/mods.toml} must be gone, not merely superseded. A jar carrying
     * both loads as far as the main menu and then behaves as though the mod were absent.
     */
    @Test
    void theNeoForgeFileIsTheOnlyModsMetadata() {
        Path metaInf = TestPaths.processedMainResources().resolve("META-INF");
        assertTrue(Files.isRegularFile(metaInf.resolve("neoforge.mods.toml")));
        assertFalse(Files.exists(metaInf.resolve("mods.toml")),
                "META-INF/mods.toml is the Forge 1.20.1 spelling and must not be produced");
    }

    /** Nothing above should have been readable at all if the file were not valid UTF-8 TOML. */
    @Test
    void everyPlaceholderWasExpanded() throws IOException {
        String raw = Files.readString(TestPaths.processedMainResources().resolve(METADATA),
                StandardCharsets.UTF_8);
        assertFalse(raw.contains("${"),
                "an unexpanded ${...} placeholder survived processResources; add the property to the "
                        + "replaceProperties map in build.gradle");
        // Sanity: the parse above is only meaningful if the file has content beyond comments.
        assertFalse(Map.of().equals(metadata().valueMap()), "the metadata parsed to nothing");
    }
}
