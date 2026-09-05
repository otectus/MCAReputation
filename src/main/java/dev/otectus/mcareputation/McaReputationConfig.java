package dev.otectus.mcareputation;

import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.function.Supplier;

/**
 * Server-authoritative common configuration and client presentation configuration (spec §23).
 *
 * <p>Two properties matter more than the individual options:
 *
 * <ol>
 *   <li><b>Every read is safe before the config is loaded.</b> {@link #read} swallows the
 *       {@code IllegalStateException} Forge throws for an unloaded spec and returns the documented
 *       default. Pure domain tests, early classloading, and datagen therefore all see sane values
 *       instead of an exception, and no caller has to remember to guard.</li>
 *   <li><b>Config may tighten a bound, never loosen one.</b> Every accessor that feeds a stored
 *       collection or a score is clamped against {@link ReputationBounds}, so hand-editing the TOML
 *       cannot produce an unbounded witness list or a score that overflows an {@code int} (§23.1).</li>
 * </ol>
 *
 * <p>Disabling a subsystem changes behaviour only. Nothing here deletes saved records: a server owner
 * who turns decay off and back on gets the same ledger they left (§23.1).
 */
public final class McaReputationConfig {

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<Common, ModConfigSpec> common = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();

        Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private McaReputationConfig() {
    }

    /**
     * Reads a config value, falling back to {@code fallback} when the spec is not loaded yet (or at
     * all, as in unit tests and on a client that never joined a server).
     */
    private static <T> T read(Supplier<T> value, T fallback) {
        try {
            T read = value.get();
            return read == null ? fallback : read;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Common (server-authoritative)
    // ------------------------------------------------------------------

    public static final class Common {

        public final ModConfigSpec.BooleanValue enableReputation;
        public final ModConfigSpec.IntValue minimumScore;
        public final ModConfigSpec.IntValue maximumScore;
        public final ModConfigSpec.IntValue defaultVillageSearchRadius;
        public final ModConfigSpec.BooleanValue enableCoreAssaultIncidents;
        public final ModConfigSpec.BooleanValue enableCoreKillingIncidents;
        public final ModConfigSpec.BooleanValue enableCoreRescueIncidents;
        public final ModConfigSpec.BooleanValue enableCoreCureIncidents;
        public final ModConfigSpec.BooleanValue enableCoreRaidIncidents;
        public final ModConfigSpec.BooleanValue enableCorePvpIncidents;
        public final ModConfigSpec.IntValue rescueThreatWindowTicks;
        public final ModConfigSpec.IntValue rescueCoalesceTicks;
        public final ModConfigSpec.DoubleValue minimumIncidentDamage;
        public final ModConfigSpec.IntValue witnessRadius;
        public final ModConfigSpec.IntValue maxWitnesses;
        public final ModConfigSpec.BooleanValue requireWitnessLineOfSight;
        public final ModConfigSpec.BooleanValue attributeTamedDamage;
        public final ModConfigSpec.IntValue selfDefenseWindowTicks;
        public final ModConfigSpec.DoubleValue selfDefenseMultiplier;
        public final ModConfigSpec.IntValue assaultCoalesceTicks;
        public final ModConfigSpec.IntValue minRumorDelayTicks;
        public final ModConfigSpec.IntValue maxRumorDelayTicks;
        public final ModConfigSpec.BooleanValue enableVillagerOpinion;
        public final ModConfigSpec.IntValue opinionHearsayPercent;
        public final ModConfigSpec.IntValue opinionInvolvedPercent;
        public final ModConfigSpec.IntValue maxIncidentsPerCommunity;
        public final ModConfigSpec.IntValue maxIncidentsPerPlayer;
        public final ModConfigSpec.IntValue reconcileOnlineIntervalTicks;
        public final ModConfigSpec.BooleanValue enableScoreboardObjective;
        public final ModConfigSpec.ConfigValue<String> scoreboardObjectiveName;
        public final ModConfigSpec.BooleanValue enableTabListTier;
        public final ModConfigSpec.IntValue displayRefreshIntervalTicks;
        public final ModConfigSpec.BooleanValue enableScoreDecay;
        public final ModConfigSpec.BooleanValue enableTierTitles;
        public final ModConfigSpec.BooleanValue strictJsonValidation;
        public final ModConfigSpec.BooleanValue enableQuestsIntegration;
        public final ModConfigSpec.BooleanValue enableConversationsIntegration;
        public final ModConfigSpec.BooleanValue enableCrimeIntegration;
        public final ModConfigSpec.BooleanValue mirrorQuestsFallbackState;
        public final ModConfigSpec.BooleanValue migrateLegacyQuestsData;
        public final ModConfigSpec.BooleanValue debugLogging;

        Common(ModConfigSpec.Builder builder) {
            builder.comment("MCA: Reputation — server-authoritative settings.",
                    "Disabling a subsystem changes behaviour only; no saved record is ever deleted.").push("general");
            enableReputation = builder
                    .comment("Master switch. When false no incident is recorded and no score changes;",
                            "existing standing, titles, and history remain in the save untouched.")
                    .define("enableReputation", true);
            debugLogging = builder
                    .comment("Verbose DEBUG logging for MCA access failures, witness selection, dedupe",
                            "refusals, and score arithmetic. Never one line per tick.")
                    .define("debugLogging", false);
            builder.pop();

            builder.comment("Score bounds and community resolution.").push("scoring");
            minimumScore = builder
                    .comment("Lower clamp on a player's standing with one community.")
                    .defineInRange("minimumScore", ReputationBounds.DEFAULT_MIN_SCORE,
                            -ReputationBounds.ABSOLUTE_SCORE_LIMIT, 0);
            maximumScore = builder
                    .comment("Upper clamp on a player's standing with one community.",
                            "Must be greater than minimumScore; the two are swapped if inverted.")
                    .defineInRange("maximumScore", ReputationBounds.DEFAULT_MAX_SCORE,
                            0, ReputationBounds.ABSOLUTE_SCORE_LIMIT);
            defaultVillageSearchRadius = builder
                    .comment("Blocks searched for a nearby village when an action has no obvious home",
                            "community. Never used to invent an anchor community (spec 12.2).")
                    .defineInRange("defaultVillageSearchRadius", 128, 16, 512);
            enableScoreDecay = builder
                    .comment("Whether incident contributions fade according to their datapack decay policy.",
                            "Turning this off freezes contributions where they are; it does not restore",
                            "decay that has already happened.")
                    .define("enableScoreDecay", true);
            enableTierTitles = builder
                    .comment("Whether crossing a tier threshold grants that tier's title.")
                    .define("enableTierTitles", true);
            builder.pop();

            builder.comment("Automatic detection of core MCA actions (spec 20).").push("core_events");
            enableCoreAssaultIncidents = builder
                    .comment("Record an incident when a player harms an MCA villager.")
                    .define("enableCoreAssaultIncidents", true);
            enableCoreKillingIncidents = builder
                    .comment("Record an incident when a player kills an MCA villager.")
                    .define("enableCoreKillingIncidents", true);
            enableCoreRescueIncidents = builder
                    .comment("Record an incident when a player kills the mob attacking an MCA villager.")
                    .define("enableCoreRescueIncidents", true);
            enableCoreCureIncidents = builder
                    .comment("Record an incident when a player cures a zombie MCA villager.")
                    .define("enableCoreCureIncidents", true);
            enableCoreRaidIncidents = builder
                    .comment("Record an incident when a player is credited with a village surviving a raid.")
                    .define("enableCoreRaidIncidents", true);
            enableCorePvpIncidents = builder
                    .comment("Record an incident when a player kills another player where a village can see.",
                            "Off by default: on most servers duelling is not the village's business.")
                    .define("enableCorePvpIncidents", false);
            rescueThreatWindowTicks = builder
                    .comment("How recently a mob must have struck a villager for killing it to still count",
                            "as a rescue, when the mob is no longer targeting them.")
                    .defineInRange("rescueThreatWindowTicks", 100, 1, 1200);
            rescueCoalesceTicks = builder
                    .comment("Rescues of the same villager by the same player within one bucket of this",
                            "length count once, so a kited mob farm earns nothing. 0 disables bucketing.")
                    .defineInRange("rescueCoalesceTicks", 6000, 0, 72000);
            minimumIncidentDamage = builder
                    .comment("Damage below this is ignored, so chip damage and thorns do not create incidents.")
                    .defineInRange("minimumIncidentDamage", 1.0D, 0.0D, 1024.0D);
            attributeTamedDamage = builder
                    .comment("Attribute damage from a player's tamed animal to that player.")
                    .define("attributeTamedDamage", true);
            selfDefenseWindowTicks = builder
                    .comment("If the villager damaged the player within this many ticks beforehand, the",
                            "retaliation is treated as self-defence.")
                    .defineInRange("selfDefenseWindowTicks", 100, 0, 6000);
            selfDefenseMultiplier = builder
                    .comment("Penalty multiplier applied to a self-defence assault, rounded toward zero.")
                    .defineInRange("selfDefenseMultiplier", 0.25D, 0.0D, 1.0D);
            assaultCoalesceTicks = builder
                    .comment("Repeated hits on the same villager within this window are one incident, so a",
                            "sustained beating is not charged once per damage tick.")
                    .defineInRange("assaultCoalesceTicks", 200, 1, 24000);
            builder.pop();

            builder.comment("Who sees a deed, and how word gets around (spec 19).").push("witnesses");
            witnessRadius = builder
                    .comment("Block radius searched for villagers who saw an incident happen.")
                    .defineInRange("witnessRadius", 24, 1, 128);
            maxWitnesses = builder
                    .comment("Maximum witnesses stored per incident.")
                    .defineInRange("maxWitnesses", ReputationBounds.MAX_WITNESSES, 1, ReputationBounds.MAX_WITNESSES);
            requireWitnessLineOfSight = builder
                    .comment("Require unobstructed line of sight before a villager counts as a witness.")
                    .define("requireWitnessLineOfSight", true);
            minRumorDelayTicks = builder
                    .comment("Shortest deterministic delay before a non-witness resident hears a rumour.")
                    .defineInRange("minRumorDelayTicks", 6000, 0, 1_000_000);
            maxRumorDelayTicks = builder
                    .comment("Longest such delay. Clamped up to minRumorDelayTicks if set lower.")
                    .defineInRange("maxRumorDelayTicks", 48000, 0, 10_000_000);
            enableVillagerOpinion = builder
                    .comment("Derive what one villager personally makes of a player from the deeds that",
                            "villager knows about. Nothing extra is saved; turning this off only stops",
                            "the question being answered.")
                    .define("enableVillagerOpinion", true);
            opinionHearsayPercent = builder
                    .comment("Weight, in percent, of a deed a villager only heard about — and of the",
                            "community baseline, which reaches them the same way.")
                    .defineInRange("opinionHearsayPercent", 50, 0, 100);
            opinionInvolvedPercent = builder
                    .comment("Weight, in percent, of a deed the villager was a subject of. Above 100",
                            "because what was done to you counts for more than what you watched.")
                    .defineInRange("opinionInvolvedPercent", 150, 100, 300);
            builder.pop();

            builder.comment("Storage bounds. These may only tighten the hard caps in the source.").push("limits");
            maxIncidentsPerCommunity = builder
                    .comment("Incidents retained for one player in one community.")
                    .defineInRange("maxIncidentsPerCommunity", ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY,
                            1, ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY);
            maxIncidentsPerPlayer = builder
                    .comment("Incidents retained across all of one player's communities.")
                    .defineInRange("maxIncidentsPerPlayer", ReputationBounds.MAX_INCIDENTS_PER_PLAYER,
                            1, ReputationBounds.MAX_INCIDENTS_PER_PLAYER);
            reconcileOnlineIntervalTicks = builder
                    .comment("How often decay is reconciled for online players. This is a bounded sweep over",
                            "online players only — never a scan of the save or of the world.")
                    .defineInRange("reconcileOnlineIntervalTicks", 1200, 20, 72000);
            strictJsonValidation = builder
                    .comment("Treat any datapack validation error as a failed reload. The previously loaded",
                            "registries stay live either way; strict mode simply refuses to swap them.")
                    .define("strictJsonValidation", false);
            builder.pop();

            builder.comment("Showing standing outside the standing screen. Both displays are off by",
                    "default: they are always-on surfaces, and a server that wants one will say so.")
                    .push("visibility");
            enableScoreboardObjective = builder
                    .comment("Keep a dummy scoreboard objective holding each online player's standing with",
                            "the village the standing screen would open on. The objective is created and",
                            "written; which slot it is displayed in, if any, is left to the server.")
                    .define("enableScoreboardObjective", false);
            scoreboardObjectiveName = builder
                    .comment("Name of that objective. An existing objective with this name is used as it is,",
                            "never recreated.")
                    .define("scoreboardObjectiveName", "mcareputation",
                            value -> value instanceof String name && !name.isBlank());
            enableTabListTier = builder
                    .comment("Append the tier name to each player's tab-list entry. Appended to whatever",
                            "name other mods produced, so it stacks rather than overwrites.")
                    .define("enableTabListTier", false);
            displayRefreshIntervalTicks = builder
                    .comment("How often the displays are swept for online players. Standing changes and",
                            "dimension changes update immediately; this sweep exists because walking into",
                            "another village raises no event.")
                    .defineInRange("displayRefreshIntervalTicks", 100, 20, 1200);
            builder.pop();

            builder.comment("Optional companion mods. Each is a no-op when that mod is absent.").push("integration");
            enableQuestsIntegration = builder
                    .comment("Accept reputation outcomes from MCA: Quests and serve its bridge.")
                    .define("enableQuestsIntegration", true);
            enableConversationsIntegration = builder
                    .comment("Serve dialogue context, check bias, and gossip candidates to MCA: Conversations.")
                    .define("enableConversationsIntegration", true);
            enableCrimeIntegration = builder
                    .comment("Accept incidents and resolutions from MCA: Crime, and let it claim ownership of",
                            "villager assault/death detection so one attack is not counted by both mods.",
                            "Turning this off makes Crime's authority claim fail, and native detection resumes",
                            "for the next event -- there is never a window where neither mod is watching.")
                    .define("enableCrimeIntegration", true);
            mirrorQuestsFallbackState = builder
                    .comment("After each canonical commit, mirror score/tier/title into Quests' own fallback",
                            "store so removing this mod leaves Quests with sensible standing.")
                    .define("mirrorQuestsFallbackState", true);
            migrateLegacyQuestsData = builder
                    .comment("Import a pre-Reputation world's shared Quests village scores into per-player",
                            "baselines exactly once per player (spec 32.2).")
                    .define("migrateLegacyQuestsData", true);
            builder.pop();
        }
    }

    // ------------------------------------------------------------------
    // Client (presentation only — server logic never reads these)
    // ------------------------------------------------------------------

    public static final class Client {

        public final ModConfigSpec.BooleanValue showReputationButton;
        public final ModConfigSpec.BooleanValue showVillagerOpinion;
        public final ModConfigSpec.BooleanValue showChangeActionBar;
        public final ModConfigSpec.BooleanValue showTierToasts;
        public final ModConfigSpec.BooleanValue showNegativeTierMessages;
        public final ModConfigSpec.BooleanValue mergeChangeNotifications;
        public final ModConfigSpec.BooleanValue showExactScore;
        public final ModConfigSpec.BooleanValue showIncidentDeltas;

        Client(ModConfigSpec.Builder builder) {
            builder.comment("MCA: Reputation — client presentation.",
                    "Hiding a number changes only what you see; the server's arithmetic is unaffected.")
                    .push("display");
            showReputationButton = builder
                    .comment("Add a Standing button to MCA's villager interaction screen.")
                    .define("showReputationButton", true);
            showChangeActionBar = builder
                    .comment("Show routine standing changes as a single merged action-bar line.")
                    .define("showChangeActionBar", true);
            showTierToasts = builder
                    .comment("Show a toast the first time you reach a new best tier with a village.")
                    .define("showTierToasts", true);
            showNegativeTierMessages = builder
                    .comment("Show a subdued message when your standing falls to a lower tier.")
                    .define("showNegativeTierMessages", true);
            mergeChangeNotifications = builder
                    .comment("Combine several changes that land at once into one message.")
                    .define("mergeChangeNotifications", true);
            showExactScore = builder
                    .comment("Show the numeric score. When false, standing is described by tier name and",
                            "qualitative progress only.")
                    .define("showExactScore", true);
            showIncidentDeltas = builder
                    .comment("Show each deed's numeric contribution in the standing screen.")
                    .define("showIncidentDeltas", true);
            showVillagerOpinion = builder
                    .comment("When the screen was opened from a villager, show what that villager",
                            "personally makes of you beneath the village's own view.")
                    .define("showVillagerOpinion", true);
            builder.pop();
        }
    }

    // ------------------------------------------------------------------
    // Test overrides
    // ------------------------------------------------------------------

    /**
     * Headless test seam. Unit tests cannot load a Forge config file, so off-game every accessor
     * reports its shipped default — which would leave one whole side of every config-guarded branch
     * (decay off, titles off, the mod disabled) untestable. A test sets the field it needs and calls
     * {@link #reset()} in teardown. Production code never touches this; the fields stay {@code null}
     * outside tests and cost one null check.
     */
    public static final class TestOverrides {

        public static volatile Boolean enabled;
        public static volatile Boolean scoreDecay;
        public static volatile Boolean tierTitles;
        public static volatile Boolean migrateLegacyQuestsData;
        public static volatile Boolean questsIntegration;
        public static volatile Boolean conversationsIntegration;
        public static volatile Boolean crimeIntegration;

        private TestOverrides() {
        }

        /** Clears every override; call from test teardown so no override leaks across tests. */
        public static void reset() {
            enabled = null;
            scoreDecay = null;
            tierTitles = null;
            migrateLegacyQuestsData = null;
            questsIntegration = null;
            conversationsIntegration = null;
            crimeIntegration = null;
        }
    }

    // ------------------------------------------------------------------
    // Safe accessors — every caller uses these, never the raw ConfigValues
    // ------------------------------------------------------------------

    public static boolean enabled() {
        if (TestOverrides.enabled != null) {
            return TestOverrides.enabled;
        }
        return read(COMMON.enableReputation::get, true);
    }

    public static boolean debugLogging() {
        return read(COMMON.debugLogging::get, false);
    }

    /** Lower score clamp. Guaranteed strictly below {@link #maximumScore()} (§23.1). */
    public static int minimumScore() {
        int min = read(COMMON.minimumScore::get, ReputationBounds.DEFAULT_MIN_SCORE);
        int max = read(COMMON.maximumScore::get, ReputationBounds.DEFAULT_MAX_SCORE);
        return Math.min(min, max);
    }

    /** Upper score clamp. Guaranteed strictly above {@link #minimumScore()} (§23.1). */
    public static int maximumScore() {
        int min = read(COMMON.minimumScore::get, ReputationBounds.DEFAULT_MIN_SCORE);
        int max = read(COMMON.maximumScore::get, ReputationBounds.DEFAULT_MAX_SCORE);
        return min == max ? max + 1 : Math.max(min, max);
    }

    public static int villageSearchRadius() {
        return Math.max(16, Math.min(512, read(COMMON.defaultVillageSearchRadius::get, 128)));
    }

    public static boolean coreAssaultEnabled() {
        return read(COMMON.enableCoreAssaultIncidents::get, true);
    }

    public static boolean coreKillingEnabled() {
        return read(COMMON.enableCoreKillingIncidents::get, true);
    }

    public static boolean coreRescueEnabled() {
        return read(COMMON.enableCoreRescueIncidents::get, true);
    }

    public static boolean coreCureEnabled() {
        return read(COMMON.enableCoreCureIncidents::get, true);
    }

    public static boolean coreRaidEnabled() {
        return read(COMMON.enableCoreRaidIncidents::get, true);
    }

    /** Off by default; a duel is not the village's business unless an operator says it is. */
    public static boolean corePvpEnabled() {
        return read(COMMON.enableCorePvpIncidents::get, false);
    }

    public static int rescueThreatWindowTicks() {
        return Math.max(1, Math.min(1200, read(COMMON.rescueThreatWindowTicks::get, 100)));
    }

    /** Bucket length for rescue dedupe. {@code 0} means every rescue is its own deed. */
    public static int rescueCoalesceTicks() {
        return Math.max(0, Math.min(72000, read(COMMON.rescueCoalesceTicks::get, 6000)));
    }

    public static double minimumIncidentDamage() {
        double value = read(COMMON.minimumIncidentDamage::get, 1.0D);
        return Double.isFinite(value) ? Math.max(0.0D, value) : 1.0D;
    }

    public static int witnessRadius() {
        return Math.max(1, Math.min(128, read(COMMON.witnessRadius::get, 24)));
    }

    public static int maxWitnesses() {
        return Math.max(1, Math.min(ReputationBounds.MAX_WITNESSES,
                read(COMMON.maxWitnesses::get, ReputationBounds.MAX_WITNESSES)));
    }

    public static boolean requireWitnessLineOfSight() {
        return read(COMMON.requireWitnessLineOfSight::get, true);
    }

    public static boolean attributeTamedDamage() {
        return read(COMMON.attributeTamedDamage::get, true);
    }

    public static int selfDefenseWindowTicks() {
        return Math.max(0, read(COMMON.selfDefenseWindowTicks::get, 100));
    }

    public static double selfDefenseMultiplier() {
        double value = read(COMMON.selfDefenseMultiplier::get, 0.25D);
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 0.25D;
    }

    public static int assaultCoalesceTicks() {
        return Math.max(1, read(COMMON.assaultCoalesceTicks::get, 200));
    }

    public static int minRumorDelayTicks() {
        return Math.max(0, Math.min(minRumorRaw(), maxRumorRaw()));
    }

    /** Always ≥ {@link #minRumorDelayTicks()}, even if the TOML was hand-edited the wrong way round. */
    public static int maxRumorDelayTicks() {
        return Math.max(0, Math.max(minRumorRaw(), maxRumorRaw()));
    }

    private static int minRumorRaw() {
        return read(COMMON.minRumorDelayTicks::get, 6000);
    }

    private static int maxRumorRaw() {
        return read(COMMON.maxRumorDelayTicks::get, 48000);
    }

    /** Whether per-villager opinion is answered at all. Nothing is stored either way. */
    public static boolean villagerOpinionEnabled() {
        return read(COMMON.enableVillagerOpinion::get, true);
    }

    /** Weight of hearsay, and of the baseline, in percent. */
    public static int opinionHearsayPercent() {
        return Math.max(0, Math.min(100, read(COMMON.opinionHearsayPercent::get, 50)));
    }

    /** Weight of a deed the villager was a subject of, in percent. Never below full weight. */
    public static int opinionInvolvedPercent() {
        return Math.max(100, Math.min(300, read(COMMON.opinionInvolvedPercent::get, 150)));
    }

    public static int maxIncidentsPerCommunity() {
        return Math.max(1, Math.min(ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY,
                read(COMMON.maxIncidentsPerCommunity::get, ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY)));
    }

    public static int maxIncidentsPerPlayer() {
        return Math.max(1, Math.min(ReputationBounds.MAX_INCIDENTS_PER_PLAYER,
                read(COMMON.maxIncidentsPerPlayer::get, ReputationBounds.MAX_INCIDENTS_PER_PLAYER)));
    }

    public static int reconcileOnlineIntervalTicks() {
        return Math.max(20, read(COMMON.reconcileOnlineIntervalTicks::get, 1200));
    }

    /** Whether the scoreboard objective is maintained at all. */
    public static boolean scoreboardObjectiveEnabled() {
        return read(COMMON.enableScoreboardObjective::get, false);
    }

    /** Objective name; never blank, since a blank one cannot be created. */
    public static String scoreboardObjectiveName() {
        String name = read(COMMON.scoreboardObjectiveName::get, "mcareputation");
        return name.isBlank() ? "mcareputation" : name;
    }

    /** Whether the tier name is appended to tab-list entries. */
    public static boolean tabListTierEnabled() {
        return read(COMMON.enableTabListTier::get, false);
    }

    public static int displayRefreshIntervalTicks() {
        return Math.max(20, Math.min(1200, read(COMMON.displayRefreshIntervalTicks::get, 100)));
    }

    public static boolean scoreDecayEnabled() {
        if (TestOverrides.scoreDecay != null) {
            return TestOverrides.scoreDecay;
        }
        return read(COMMON.enableScoreDecay::get, true);
    }

    public static boolean tierTitlesEnabled() {
        if (TestOverrides.tierTitles != null) {
            return TestOverrides.tierTitles;
        }
        return read(COMMON.enableTierTitles::get, true);
    }

    public static boolean strictJsonValidation() {
        return read(COMMON.strictJsonValidation::get, false);
    }

    public static boolean questsIntegrationEnabled() {
        if (TestOverrides.questsIntegration != null) {
            return TestOverrides.questsIntegration;
        }
        return read(COMMON.enableQuestsIntegration::get, true);
    }

    public static boolean conversationsIntegrationEnabled() {
        if (TestOverrides.conversationsIntegration != null) {
            return TestOverrides.conversationsIntegration;
        }
        return read(COMMON.enableConversationsIntegration::get, true);
    }

    public static boolean crimeIntegrationEnabled() {
        if (TestOverrides.crimeIntegration != null) {
            return TestOverrides.crimeIntegration;
        }
        return read(COMMON.enableCrimeIntegration::get, true);
    }

    public static boolean mirrorQuestsFallbackState() {
        return read(COMMON.mirrorQuestsFallbackState::get, true);
    }

    public static boolean migrateLegacyQuestsData() {
        if (TestOverrides.migrateLegacyQuestsData != null) {
            return TestOverrides.migrateLegacyQuestsData;
        }
        return read(COMMON.migrateLegacyQuestsData::get, true);
    }

    // --- client -------------------------------------------------------------

    public static boolean showReputationButton() {
        return read(CLIENT.showReputationButton::get, true);
    }

    public static boolean showChangeActionBar() {
        return read(CLIENT.showChangeActionBar::get, true);
    }

    public static boolean showTierToasts() {
        return read(CLIENT.showTierToasts::get, true);
    }

    public static boolean showNegativeTierMessages() {
        return read(CLIENT.showNegativeTierMessages::get, true);
    }

    public static boolean mergeChangeNotifications() {
        return read(CLIENT.mergeChangeNotifications::get, true);
    }

    public static boolean showExactScore() {
        return read(CLIENT.showExactScore::get, true);
    }

    public static boolean showIncidentDeltas() {
        return read(CLIENT.showIncidentDeltas::get, true);
    }

    public static boolean showVillagerOpinion() {
        return read(CLIENT.showVillagerOpinion::get, true);
    }
}
