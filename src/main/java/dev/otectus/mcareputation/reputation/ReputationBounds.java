package dev.otectus.mcareputation.reputation;

/**
 * The hard structural limits on stored reputation state (spec §13.5).
 *
 * <p>These are <b>ceilings, not settings</b>. {@code McaReputationConfig} exposes some of the same
 * quantities so a server owner can make them <em>smaller</em>; nothing — not config, not a datapack,
 * not an API caller — may exceed the values here. A save file whose size depends on playtime without
 * bound is a defect (§34), so every collection that grows with play is capped in one place.
 *
 * <p>Kept free of Minecraft and Forge types so the pure domain tests can assert against it directly.
 */
public final class ReputationBounds {

    private ReputationBounds() {
    }

    /** Default score clamp, overridable downward by config (§17.1). */
    public static final int DEFAULT_MIN_SCORE = -1000;
    public static final int DEFAULT_MAX_SCORE = 1000;

    /** Absolute clamp beyond which config may not go, in either direction. */
    public static final int ABSOLUTE_SCORE_LIMIT = 1_000_000;

    /** Maximum incidents retained for one player in one community (§13.5). */
    public static final int MAX_INCIDENTS_PER_COMMUNITY = 64;

    /** Maximum incidents retained across all of one player's communities (§13.5). */
    public static final int MAX_INCIDENTS_PER_PLAYER = 512;

    /** Maximum witnesses stored on a single incident (§13.5, §19.1). */
    public static final int MAX_WITNESSES = 32;

    /** Maximum subjects stored on a single incident (§13.5). */
    public static final int MAX_SUBJECTS = 4;

    /** Maximum context entries stored on a single incident (§13.5). */
    public static final int MAX_CONTEXT_KEYS = 16;

    /** Maximum length of a context key (§13.5). */
    public static final int MAX_CONTEXT_KEY_LENGTH = 64;

    /** Maximum length of a context value or serialized component (§13.5). */
    public static final int MAX_CONTEXT_VALUE_LENGTH = 4096;

    /** Maximum length of a dedupe key (§13.5). */
    public static final int MAX_DEDUPE_KEY_LENGTH = 256;

    /** Maximum communities tracked for one player before the least recently touched are dropped. */
    public static final int MAX_COMMUNITIES_PER_PLAYER = 256;

    /** Maximum titles of one scope held by one player. */
    public static final int MAX_TITLES = 64;

    /** Maximum per-ladder tier high-water entries stored for one community record. */
    public static final int MAX_LADDER_HIGH_WATER = 16;

    /** Maximum incident summaries sent in one snapshot packet (§27.3). */
    public static final int MAX_SYNCED_INCIDENTS = 50;

    /** Maximum communities listed in one snapshot packet (§27.3). */
    public static final int MAX_SYNCED_COMMUNITIES = 64;

    /**
     * Recent dedupe keys indexed per player for fast rejection. The index is rebuilt from the records
     * themselves on load and is therefore a cache, never a source of truth (§14.2).
     */
    public static final int MAX_DEDUPE_INDEX_PER_PLAYER = 512;
}
