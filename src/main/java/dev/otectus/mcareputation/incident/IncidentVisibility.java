package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import dev.otectus.mcareputation.util.EnumCodecs;

import java.util.Locale;
import java.util.Optional;

/**
 * Who can initially know an incident (spec §19.2). Visibility is decided once, when the incident is
 * created, and never re-evaluated; how knowledge <em>spreads</em> from there is
 * {@link AwarenessResolver}'s job.
 */
public enum IncidentVisibility {

    /**
     * A narrative obligation or private memory. Only subjects explicitly recorded as knowing it may
     * use it, and it never spreads. Private incidents contribute {@code 0} to public standing by
     * construction (§14.1) — they are memory, not reputation.
     */
    PRIVATE,

    /**
     * Witnesses know it at once; every other resident of the community learns it after a
     * deterministic per-villager delay (§19.3). A witnessed incident with no surviving witness is
     * either dropped or retained as hidden, zero-contribution history (§19.1).
     */
    WITNESSED,

    /** Public business. Every current resident of the community knows it immediately. */
    VILLAGE,

    /**
     * Reserved for a future cross-village fame system (§6 non-goal 6). Accepted by the API, the
     * codecs, and saved data so packs written against a later version still load, but treated
     * exactly as {@link #VILLAGE} everywhere in 0.1.0. Spelled {@code "global"} in JSON.
     */
    GLOBAL_RESERVED;

    private static final String GLOBAL_JSON = "global";

    public static final Codec<IncidentVisibility> CODEC =
            EnumCodecs.codec(IncidentVisibility.class, IncidentVisibility::jsonName);

    /** The JSON/NBT spelling. {@code GLOBAL_RESERVED} is written as {@code "global"} per §7/§14. */
    public String jsonName() {
        return this == GLOBAL_RESERVED ? GLOBAL_JSON : name().toLowerCase(Locale.ROOT);
    }

    public static Optional<IncidentVisibility> byName(String raw) {
        return EnumCodecs.byName(IncidentVisibility.class, IncidentVisibility::jsonName, raw);
    }

    /** Lenient saved-data read (§13.6): unknown values degrade to the most restrictive visibility. */
    public static IncidentVisibility byNameOr(String raw, IncidentVisibility fallback) {
        return byName(raw).orElse(fallback);
    }

    /**
     * The visibility this value actually behaves as in 0.1.0. {@code GLOBAL_RESERVED} collapses to
     * {@link #VILLAGE}; everything else is itself. Every awareness/UI/gossip decision goes through
     * here so the reserved value can never accidentally become a third code path.
     */
    public IncidentVisibility effective() {
        return this == GLOBAL_RESERVED ? VILLAGE : this;
    }

    /** True when this visibility can ever produce a non-zero public score contribution (§14.1). */
    public boolean isPublic() {
        return effective() != PRIVATE;
    }
}
