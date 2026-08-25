package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * How much of an incident's original weight survives each kind of resolution (spec §15.2).
 *
 * <p>A multiplier of {@code 0.75} for {@code apologized} means an apology leaves three quarters of the
 * penalty in place — the deed is acknowledged, not undone. {@code 0.0} means the contribution goes
 * away entirely while the record itself remains, which is what "forgiven" and "disproven" should feel
 * like: the village stops holding it against you, but it still happened (or still stands accused).
 *
 * <p>Rounding is deliberately <b>toward zero</b> (§15.2), so partial resolutions of small incidents
 * always reduce rather than getting stuck by integer truncation in the wrong direction.
 */
public record ResolutionPolicy(Map<IncidentStatus, Float> multipliers) {

    /**
     * The defaults applied when a definition omits {@code resolution} entirely. Chosen so that every
     * incident type is resolvable by an authored quest outcome out of the box.
     */
    public static final ResolutionPolicy DEFAULT = new ResolutionPolicy(Map.of(
            IncidentStatus.APOLOGIZED, 0.75f,
            IncidentStatus.ATONED, 0.25f,
            IncidentStatus.FORGIVEN, 0.0f,
            IncidentStatus.DISPROVEN, 0.0f));

    public static final Codec<ResolutionPolicy> CODEC = Codec
            .unboundedMap(IncidentStatus.CODEC, Codec.FLOAT)
            .flatXmap(ResolutionPolicy::validate, policy -> DataResult.success(policy.multipliers()));

    public ResolutionPolicy {
        EnumMap<IncidentStatus, Float> copy = new EnumMap<>(IncidentStatus.class);
        if (multipliers != null) {
            multipliers.forEach((status, value) -> {
                if (status != null && value != null) {
                    copy.put(status, clampUnit(value));
                }
            });
        }
        multipliers = Map.copyOf(copy);
    }

    private static float clampUnit(float value) {
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /** §21.3: resolution multipliers must be {@code 0.0..1.0}, and only real resolutions are settable. */
    private static DataResult<ResolutionPolicy> validate(Map<IncidentStatus, Float> raw) {
        for (Map.Entry<IncidentStatus, Float> entry : raw.entrySet()) {
            IncidentStatus status = entry.getKey();
            Float value = entry.getValue();
            if (status == IncidentStatus.ACTIVE) {
                return DataResult.error(() -> "resolution cannot define a multiplier for 'active'"
                        + " — that is the unresolved state");
            }
            if (value == null || Float.isNaN(value) || value < 0.0f || value > 1.0f) {
                return DataResult.error(() -> "resolution." + status.jsonName()
                        + " must be between 0.0 and 1.0, got " + value);
            }
        }
        return DataResult.success(new ResolutionPolicy(raw));
    }

    /**
     * The multiplier for a status, or {@code 1.0} when the definition does not describe it — an
     * unlisted resolution changes the status for narrative purposes but leaves the score alone, which
     * is the conservative reading and never hands out an unauthored pardon.
     */
    public float multiplierFor(IncidentStatus status) {
        return multipliers.getOrDefault(status, 1.0f);
    }

    /**
     * The contribution an incident settles at once {@code status} is applied, rounded toward zero.
     * Pure; {@code baseDelta} is the incident's original delta, not its currently decayed value, so
     * resolving is idempotent regardless of how much decay has already happened. The arithmetic is
     * {@link dev.otectus.mcareputation.reputation.ReputationMath#scaleTowardZero} — one
     * implementation, one set of NaN/overflow guards, rather than a private re-derivation here.
     */
    public int settledDelta(int baseDelta, IncidentStatus status) {
        if (baseDelta == 0 || !status.isResolved()) {
            return baseDelta;
        }
        return dev.otectus.mcareputation.reputation.ReputationMath
                .scaleTowardZero(baseDelta, multiplierFor(status));
    }

    public boolean isEmpty() {
        return multipliers.isEmpty();
    }
}
