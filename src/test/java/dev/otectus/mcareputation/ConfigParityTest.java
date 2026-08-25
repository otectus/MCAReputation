package dev.otectus.mcareputation;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §23 and port acceptance: an existing server's {@code mcareputation-common.toml} and an existing
 * client's {@code mcareputation-client.toml} must keep working untouched after the NeoForge port.
 *
 * <p>{@code ForgeConfigSpec} became {@code ModConfigSpec}, and the two APIs are close enough that a
 * dropped or renamed key would compile cleanly and only surface as a silently reset option on
 * somebody's server. So the key set and every default are pinned here explicitly rather than derived
 * from the spec — a test that read the spec to check the spec would pass no matter what changed.
 */
class ConfigParityTest {

    /**
     * Every path in the common spec, in {@code section.key} form.
     *
     * <p>Adding an option is a deliberate act: add it here in the same commit, and note it in
     * CONFIG.md. Removing or renaming one is a breaking change to a file players already have.
     */
    private static final List<String> COMMON_KEYS = List.of(
            "general.enableReputation",
            "general.debugLogging",
            "scoring.minimumScore",
            "scoring.maximumScore",
            "scoring.defaultVillageSearchRadius",
            "scoring.enableScoreDecay",
            "scoring.enableTierTitles",
            "core_events.enableCoreAssaultIncidents",
            "core_events.enableCoreKillingIncidents",
            "core_events.minimumIncidentDamage",
            "core_events.attributeTamedDamage",
            "core_events.selfDefenseWindowTicks",
            "core_events.selfDefenseMultiplier",
            "core_events.assaultCoalesceTicks",
            "witnesses.witnessRadius",
            "witnesses.maxWitnesses",
            "witnesses.requireWitnessLineOfSight",
            "witnesses.minRumorDelayTicks",
            "witnesses.maxRumorDelayTicks",
            "limits.maxIncidentsPerCommunity",
            "limits.maxIncidentsPerPlayer",
            "limits.reconcileOnlineIntervalTicks",
            "limits.strictJsonValidation",
            "integration.enableQuestsIntegration",
            "integration.enableConversationsIntegration",
            "integration.enableCrimeIntegration",
            "integration.mirrorQuestsFallbackState",
            "integration.migrateLegacyQuestsData");

    /** Every path in the client spec. Same contract, same reason. */
    private static final List<String> CLIENT_KEYS = List.of(
            "display.showReputationButton",
            "display.showChangeActionBar",
            "display.showTierToasts",
            "display.showNegativeTierMessages",
            "display.mergeChangeNotifications",
            "display.showExactScore",
            "display.showIncidentDeltas");

    private static List<String> pathsOf(ModConfigSpec spec) {
        List<String> paths = new ArrayList<>();
        collect(spec.getValues(), "", paths);
        paths.sort(String::compareTo);
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static void collect(com.electronwill.nightconfig.core.UnmodifiableConfig config,
                                String prefix, List<String> into) {
        config.valueMap().forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested) {
                collect(nested, path, into);
            } else {
                into.add(path);
            }
        });
    }

    @Test
    void bothSpecsExistAndAreDistinct() {
        assertNotNull(McaReputationConfig.COMMON_SPEC, "the common spec must survive the port");
        assertNotNull(McaReputationConfig.CLIENT_SPEC, "the client spec must survive the port");
        assertFalse(McaReputationConfig.COMMON_SPEC == McaReputationConfig.CLIENT_SPEC,
                "common and client must stay separate specs so a client never carries server rules");
    }

    /** Neither spec may be empty — a builder mistake during the port would produce exactly that. */
    @Test
    void neitherSpecIsEmpty() {
        assertFalse(pathsOf(McaReputationConfig.COMMON_SPEC).isEmpty(),
                "the common spec defines no keys at all");
        assertFalse(pathsOf(McaReputationConfig.CLIENT_SPEC).isEmpty(),
                "the client spec defines no keys at all");
    }

    /**
     * The common key set must be exactly unchanged — not merely a superset.
     *
     * <p>An exact comparison is the point. A missing key silently resets a setting an existing server
     * already chose; an unexpected extra one means an option was added without being written down in
     * CONFIG.md. Both are things to notice deliberately rather than discover in a bug report.
     */
    @Test
    void theCommonKeySetIsExactlyUnchanged() {
        assertEquals(COMMON_KEYS.stream().sorted().toList(),
                pathsOf(McaReputationConfig.COMMON_SPEC),
                "the common config key set changed; update CONFIG.md and this list together");
    }

    @Test
    void theClientKeySetIsExactlyUnchanged() {
        assertEquals(CLIENT_KEYS.stream().sorted().toList(),
                pathsOf(McaReputationConfig.CLIENT_SPEC),
                "the client config key set changed; update CONFIG.md and this list together");
    }

    /** No key may be defined twice under different sections, which would make the TOML ambiguous. */
    @Test
    void noConfigPathIsDuplicated() {
        for (ModConfigSpec spec : List.of(McaReputationConfig.COMMON_SPEC,
                McaReputationConfig.CLIENT_SPEC)) {
            List<String> paths = pathsOf(spec);
            assertEquals(paths.size(), Set.copyOf(paths).size(),
                    () -> "duplicate config path in " + paths);
        }
    }

    /**
     * §23: every accessor must return its documented default before the spec is loaded.
     *
     * <p>This is what lets the pure-domain tests and early classloading read config at all. The port
     * kept {@code read(...)}'s catch-all for exactly this reason: {@code ModConfigSpec} throws a
     * different exception than {@code ForgeConfigSpec} did for an unloaded value, and a narrower
     * catch would have turned every one of these into a crash.
     */
    @Test
    void everyAccessorIsSafeAndSaneBeforeTheSpecLoads() {
        assertTrue(McaReputationConfig.enabled(), "the mod defaults to on");
        assertFalse(McaReputationConfig.debugLogging(), "debug logging defaults to off");

        assertTrue(McaReputationConfig.minimumIncidentDamage() > 0.0D,
                "a zero threshold would make every chip of damage a public deed");
        assertTrue(McaReputationConfig.reconcileOnlineIntervalTicks() > 0,
                "a non-positive interval would run the periodic task every tick");

        // Clamped against ReputationBounds rather than trusted: config may tighten, never loosen.
        assertTrue(McaReputationConfig.maxIncidentsPerCommunity()
                        <= dev.otectus.mcareputation.reputation.ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY,
                "config must not be able to raise a stored-collection bound");
        assertTrue(McaReputationConfig.maxWitnesses()
                        <= dev.otectus.mcareputation.reputation.ReputationBounds.MAX_WITNESSES,
                "config must not be able to raise the witness cap");
    }

    /** The two file names are part of the upgrade contract: an existing TOML must still be found. */
    @Test
    void theConfigFileNamesAreUnchanged() throws Exception {
        String source = java.nio.file.Files.readString(
                TestPaths.mainSourceRoot().resolve("McaReputationMod.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("\"mcareputation-common.toml\""),
                "the common config filename must not change, or servers lose their settings");
        assertTrue(source.contains("\"mcareputation-client.toml\""),
                "the client config filename must not change");
    }
}
