package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.EnumCodecs;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * How an incident's contribution fades on its own (spec §15.1). Only two policies exist in 0.1.0 and
 * that is deliberate — §42 defers anything richer until the foundation is stable.
 *
 * <p>The whole design rests on one property: <b>the expected contribution is a pure function of
 * elapsed time</b>, never an accumulator that is stepped forward tick by tick. That is what lets
 * decay be reconciled lazily on query/login/UI-open (§34: no global per-tick scan) while still
 * producing exactly the same number as a hypothetical ticking implementation would.
 *
 * <p>The "elapsed" the caller passes is a <em>monotonic</em> counter maintained by
 * {@link IncidentRecord}, not {@code now - created}. Winding the world clock backwards with
 * {@code /time set} therefore adds nothing and can never restore contribution the player already lost
 * (§15.1, §33 rule 13).
 */
public record DecayPolicy(Type type, long delayTicks, int amountPerDay) {

    /** One Minecraft day. Decay steps in whole days, so a partial day never moves the number. */
    public static final long TICKS_PER_DAY = 24_000L;

    /** Datapack safety ceiling for {@code delay_ticks} — roughly 4000 in-game days. */
    public static final long MAX_DELAY_TICKS = 100_000_000L;

    /** Datapack safety ceiling for {@code amount_per_day}; anything larger is a pack error. */
    public static final int MAX_AMOUNT_PER_DAY = 1_000;

    public static final DecayPolicy NONE = new DecayPolicy(Type.NONE, 0L, 0);

    public enum Type {
        /** The contribution never changes on its own; only resolution or pruning moves it. */
        NONE,
        /** After {@code delay_ticks}, step toward zero by {@code amount_per_day} per complete day. */
        LINEAR_TO_ZERO;

        public static final Codec<Type> CODEC = EnumCodecs.codec(Type.class);

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Codec<DecayPolicy> CODEC = RecordCodecBuilder.<DecayPolicy>create(instance -> instance.group(
            StrictCodecs.strictOptional(Type.CODEC, "type", Type.NONE).forGetter(DecayPolicy::type),
            StrictCodecs.strictOptional(Codec.LONG, "delay_ticks", 0L).forGetter(DecayPolicy::delayTicks),
            StrictCodecs.strictOptional(Codec.INT, "amount_per_day", 0).forGetter(DecayPolicy::amountPerDay)
    ).apply(instance, DecayPolicy::new)).flatXmap(DecayPolicy::validate, DecayPolicy::validate);

    public DecayPolicy {
        type = type == null ? Type.NONE : type;
    }

    /**
     * §21.3: decay values must be non-negative and must terminate. A {@code linear_to_zero} policy
     * with {@code amount_per_day <= 0} would never reach zero, which is a pack authoring mistake
     * rather than a legitimate way to spell "no decay" — that is what {@code "none"} is for.
     */
    private static DataResult<DecayPolicy> validate(DecayPolicy policy) {
        if (policy.delayTicks < 0) {
            return DataResult.error(() -> "decay.delay_ticks must be >= 0, got " + policy.delayTicks);
        }
        if (policy.delayTicks > MAX_DELAY_TICKS) {
            return DataResult.error(() -> "decay.delay_ticks must be <= " + MAX_DELAY_TICKS
                    + ", got " + policy.delayTicks);
        }
        if (policy.type == Type.NONE) {
            return DataResult.success(policy);
        }
        if (policy.amountPerDay <= 0) {
            return DataResult.error(() -> "decay.amount_per_day must be > 0 for linear_to_zero"
                    + " (use {\"type\":\"none\"} for no decay), got " + policy.amountPerDay);
        }
        if (policy.amountPerDay > MAX_AMOUNT_PER_DAY) {
            return DataResult.error(() -> "decay.amount_per_day must be <= " + MAX_AMOUNT_PER_DAY
                    + ", got " + policy.amountPerDay);
        }
        return DataResult.success(policy);
    }

    public static DecayPolicy linearToZero(long delayTicks, int amountPerDay) {
        return new DecayPolicy(Type.LINEAR_TO_ZERO, Math.max(0L, delayTicks), Math.max(1, amountPerDay));
    }

    public boolean decays() {
        return type == Type.LINEAR_TO_ZERO && amountPerDay > 0;
    }

    /**
     * The contribution this incident should currently have, given the value it settled at after any
     * resolution and how long it has been alive.
     *
     * <p>Pure and total: no clamping surprises, no state, no clock read. All arithmetic is in
     * {@code long} and the result is always between {@code 0} and {@code settledDelta} inclusive, with
     * the sign of {@code settledDelta} preserved until it reaches exactly zero.
     *
     * @param settledDelta   the post-resolution base contribution (equal to the incident's base delta
     *                       while it is {@code ACTIVE})
     * @param elapsedTicks   monotonic ticks this incident has been alive; negative is treated as zero
     */
    public int contributionAt(int settledDelta, long elapsedTicks) {
        if (settledDelta == 0 || !decays() || elapsedTicks <= delayTicks) {
            return settledDelta;
        }
        long decayingTicks = elapsedTicks - delayTicks;
        long completeDays = decayingTicks / TICKS_PER_DAY;
        long reduction = completeDays * (long) amountPerDay;
        long magnitude = Math.abs((long) settledDelta) - reduction;
        if (magnitude <= 0L) {
            return 0;
        }
        return settledDelta < 0 ? (int) -magnitude : (int) magnitude;
    }

    /**
     * Ticks until {@link #contributionAt} would reach exactly zero, or {@link Long#MAX_VALUE} when it
     * never will. Used by validation to prove a policy terminates and by the UI to describe fading.
     */
    public long ticksUntilZero(int settledDelta) {
        if (settledDelta == 0) {
            return 0L;
        }
        if (!decays()) {
            return Long.MAX_VALUE;
        }
        long magnitude = Math.abs((long) settledDelta);
        long daysNeeded = (magnitude + amountPerDay - 1L) / amountPerDay; // ceil; Math.ceilDiv is Java 18+
        return delayTicks + daysNeeded * TICKS_PER_DAY;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.jsonName());
        if (delayTicks != 0L) {
            tag.putLong("delay", delayTicks);
        }
        if (amountPerDay != 0) {
            tag.putInt("perDay", amountPerDay);
        }
        return tag;
    }

    /** Lenient read (§13.6): anything unrecognised degrades to {@link #NONE}. */
    public static DecayPolicy load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return NONE;
        }
        Type type = EnumCodecs.byName(Type.class, tag.getString("type")).orElse(Type.NONE);
        if (type == Type.NONE) {
            return NONE;
        }
        return linearToZero(tag.getLong("delay"), tag.getInt("perDay"));
    }
}
