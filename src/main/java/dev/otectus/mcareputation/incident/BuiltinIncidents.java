package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.resources.ResourceLocation;

/**
 * Ids of the incident types this mod ships (spec §16), and of the sources that create them.
 *
 * <p>These are constants, not a registry: the JSON definitions in
 * {@code data/mcareputation/mcareputation/incidents/} remain the authority on what each one is
 * <em>worth</em>. Naming them here only saves the core hooks and the command tree from re-parsing
 * string literals, and gives the content validation test something concrete to check the shipped pack
 * against.
 *
 * <p>A datapack may redefine any of these. Removing one disables the core hook that uses it rather
 * than crashing — {@code ReputationService.record} refuses an unknown type with a warning.
 */
public final class BuiltinIncidents {

    private BuiltinIncidents() {
    }

    // --- core physical events (§20) ----------------------------------------

    /** Non-lethal public harm to a villager. */
    public static final ResourceLocation VILLAGER_ASSAULTED = McaReputation.id("villager_assaulted");

    /** Killing a villager. Upgrades a recent assault rather than stacking with it (§20.2). */
    public static final ResourceLocation VILLAGER_KILLED = McaReputation.id("villager_killed");

    // --- structured work, created by MCA: Quests (§16) ----------------------

    public static final ResourceLocation QUEST_COMPLETED = McaReputation.id("quest_completed");
    public static final ResourceLocation QUEST_FAILED = McaReputation.id("quest_failed");
    public static final ResourceLocation QUEST_ABANDONED = McaReputation.id("quest_abandoned");
    public static final ResourceLocation PROJECT_PHASE_COMPLETED = McaReputation.id("project_phase_completed");
    public static final ResourceLocation PROJECT_COMPLETED = McaReputation.id("project_completed");
    public static final ResourceLocation PROJECT_FAILED = McaReputation.id("project_failed");
    public static final ResourceLocation SITUATION_RESOLVED = McaReputation.id("situation_resolved");

    // --- promises and amends (§16, §31) ------------------------------------

    /** A private obligation. Zero score by construction; it is memory, not standing. */
    public static final ResourceLocation PROMISE_MADE = McaReputation.id("promise_made");
    public static final ResourceLocation PROMISE_KEPT = McaReputation.id("promise_kept");
    public static final ResourceLocation PROMISE_BROKEN = McaReputation.id("promise_broken");
    public static final ResourceLocation PUBLIC_APOLOGY = McaReputation.id("public_apology");
    public static final ResourceLocation RESTITUTION_COMPLETED = McaReputation.id("restitution_completed");

    /** Hidden marker for imported legacy standing; the number itself lives in the baseline (§32.2). */
    public static final ResourceLocation LEGACY_BALANCE = McaReputation.id("legacy_balance");

    // --- sources ------------------------------------------------------------

    /** The core damage/death hooks in this mod. */
    public static final ResourceLocation SOURCE_CORE = McaReputation.id("core");

    /** The {@code /mcareputation} command tree. */
    public static final ResourceLocation SOURCE_COMMAND = McaReputation.id("command");

    /** The legacy migration importer. */
    public static final ResourceLocation SOURCE_MIGRATION = McaReputation.id("migration");

    /** MCA: Quests, when it records through the bridge. */
    public static final ResourceLocation SOURCE_QUESTS = McaReputation.questsId("quests");

    /** MCA: Conversations, when a dialogue action records through the bridge. */
    public static final ResourceLocation SOURCE_CONVERSATIONS =
            ResourceLocation.fromNamespaceAndPath(McaReputation.CONVERSATIONS_MOD_ID, "conversations");

    // --- context keys -------------------------------------------------------

    /** Accumulated damage dealt during one coalesced assault window (§20.1). */
    public static final String CONTEXT_DAMAGE = "damage";

    /** Number of hits folded into one coalesced assault. */
    public static final String CONTEXT_HITS = "hits";

    /** Set when the penalty was reduced because the villager struck first (§20.1). */
    public static final String CONTEXT_SELF_DEFENCE = "self_defence";

    /** The assault a killing upgraded, recorded on the killing incident. */
    public static final String CONTEXT_PRECURSOR = "precursor";

    /** Written by {@code IncidentRecord.foldInto} onto an incident absorbed by a later one. */
    public static final String CONTEXT_SUPERSEDED_BY = "superseded_by";
}
