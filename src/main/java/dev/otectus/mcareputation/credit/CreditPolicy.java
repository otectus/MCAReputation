package dev.otectus.mcareputation.credit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.EnumCodecs;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A datapack-authored repeat-credit schedule (spec §10.2), loaded from
 * {@code data/<namespace>/mcareputation/credit_policies/**&#47;*.json}.
 *
 * <p>Repeat credit is the third of §10.1's three mechanisms and must not be confused with the other
 * two: deduplication recognises a <em>retry of one outcome</em>, coalescing/supersession recognises
 * <em>several signals from one encounter</em>, and this reduces the social significance of genuinely
 * different but repetitive <em>positive</em> outcomes. It never reduces accountability — that gate is
 * structural in {@link CreditDecision#applyTo(long)}.
 *
 * <p>The schedule is an explicit, finite, non-increasing basis-point list rather than a decay
 * exponent, because the authored file then states the exact percentages a pack promises: occurrence 1
 * takes index 0, and everything past the last entry takes {@link #tailBp()}. A nonzero tail permits
 * slow farming by definition and is an honest pack-author choice, not a bug.
 *
 * <p>Pure: no server, level or registry types, so {@code CreditResolverTest} runs it with no game.
 */
public record CreditPolicy(
        ResourceLocation group,
        long windowTicks,
        List<Integer> creditScheduleBp,
        int tailBp,
        Scope scope,
        Optional<SubjectLimit> subjectLimit) {

    /** One hundred percent, in basis points. Every percentage in this package is expressed this way. */
    public static final int FULL_BP = 10_000;

    /** §9.6: a credit window is 20..100000000 ticks. */
    public static final long MIN_WINDOW_TICKS = 20L;
    public static final long MAX_WINDOW_TICKS = 100_000_000L;

    /** §10.5: normalized schedule entries are bounded at 32. */
    public static final int MAX_SCHEDULE_ENTRIES = 32;

    /** How widely one allowance is shared. */
    public enum Scope {
        /** One allowance per player per community: the default, and what the shipped policies use. */
        PLAYER_COMMUNITY,
        /** One allowance per player across every community. Stricter, never more generous. */
        PLAYER_GLOBAL;

        public static final Codec<Scope> CODEC = EnumCodecs.codec(Scope.class);

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A <b>second ceiling</b> on one participant, never a separate allowance (§10.2). The effective
     * percentage is the minimum of the group and subject percentages, so cycling through fresh
     * villagers cannot evade the group-wide limit.
     */
    public record SubjectLimit(String role, List<Integer> creditScheduleBp, int tailBp) {

        public static final int MAX_ROLE_LENGTH = 48;

        public static final Codec<SubjectLimit> CODEC = RecordCodecBuilder
                .<SubjectLimit>create(instance -> instance.group(
                        Codec.STRING.fieldOf("role").forGetter(SubjectLimit::role),
                        Codec.INT.listOf().fieldOf("credit_schedule_bp")
                                .forGetter(SubjectLimit::creditScheduleBp),
                        StrictCodecs.strictOptional(Codec.INT, "tail_bp", 0)
                                .forGetter(SubjectLimit::tailBp)
                ).apply(instance, SubjectLimit::new))
                .flatXmap(SubjectLimit::validate, SubjectLimit::validate);

        public SubjectLimit {
            role = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
            creditScheduleBp = creditScheduleBp == null ? List.of() : List.copyOf(creditScheduleBp);
        }

        private static DataResult<SubjectLimit> validate(SubjectLimit limit) {
            if (limit.role.isEmpty() || limit.role.length() > MAX_ROLE_LENGTH) {
                return DataResult.error(() -> "subject_limit.role must be 1.." + MAX_ROLE_LENGTH
                        + " characters, got '" + limit.role + "'");
            }
            return validateSchedule("subject_limit.credit_schedule_bp", limit.creditScheduleBp,
                    limit.tailBp).map(ignored -> limit);
        }

        /** The percentage for a zero-based occurrence ordinal of this subject. */
        public int bpForOrdinal(int ordinal) {
            return scheduleBp(creditScheduleBp, tailBp, ordinal);
        }
    }

    public static final Codec<CreditPolicy> CODEC = RecordCodecBuilder
            .<CreditPolicy>create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("group").forGetter(CreditPolicy::group),
                    Codec.LONG.fieldOf("window_ticks").forGetter(CreditPolicy::windowTicks),
                    Codec.INT.listOf().fieldOf("credit_schedule_bp")
                            .forGetter(CreditPolicy::creditScheduleBp),
                    StrictCodecs.strictOptional(Codec.INT, "tail_bp", 0)
                            .forGetter(CreditPolicy::tailBp),
                    StrictCodecs.strictOptional(Scope.CODEC, "scope", Scope.PLAYER_COMMUNITY)
                            .forGetter(CreditPolicy::scope),
                    StrictCodecs.strictOptional(SubjectLimit.CODEC, "subject_limit")
                            .forGetter(CreditPolicy::subjectLimit)
            ).apply(instance, CreditPolicy::new))
            .flatXmap(CreditPolicy::validate, CreditPolicy::validate);

    public CreditPolicy {
        creditScheduleBp = creditScheduleBp == null ? List.of() : List.copyOf(creditScheduleBp);
        scope = scope == null ? Scope.PLAYER_COMMUNITY : scope;
        subjectLimit = subjectLimit == null ? Optional.<SubjectLimit>empty() : subjectLimit;
    }

    private static DataResult<CreditPolicy> validate(CreditPolicy policy) {
        if (policy.windowTicks < MIN_WINDOW_TICKS || policy.windowTicks > MAX_WINDOW_TICKS) {
            return DataResult.error(() -> "window_ticks must be " + MIN_WINDOW_TICKS + ".."
                    + MAX_WINDOW_TICKS + ", got " + policy.windowTicks);
        }
        return validateSchedule("credit_schedule_bp", policy.creditScheduleBp, policy.tailBp)
                .map(ignored -> policy);
    }

    /**
     * §9.6: every entry and the tail sit in {@code 0..10000} and are non-increasing. The tail
     * continues the schedule, so it may not exceed the last entry — a schedule that rose again at its
     * tail would restore full credit to a player who simply kept going.
     */
    private static DataResult<List<Integer>> validateSchedule(String field, List<Integer> schedule,
                                                              int tailBp) {
        if (schedule.isEmpty()) {
            return DataResult.error(() -> field + " must have at least one entry");
        }
        if (schedule.size() > MAX_SCHEDULE_ENTRIES) {
            return DataResult.error(() -> field + " must have at most " + MAX_SCHEDULE_ENTRIES
                    + " entries, got " + schedule.size());
        }
        String tailField = field.substring(0, field.length() - "credit_schedule_bp".length()) + "tail_bp";
        int previous = FULL_BP;
        for (int i = 0; i < schedule.size(); i++) {
            Integer entry = schedule.get(i);
            int index = i;
            int prior = previous;
            if (entry == null || entry < 0 || entry > FULL_BP) {
                return DataResult.error(() -> field + "[" + index + "] must be 0.." + FULL_BP
                        + ", got " + entry);
            }
            if (entry > prior) {
                return DataResult.error(() -> field + " must be non-increasing: entry " + index
                        + " (" + entry + ") is above the previous one (" + prior + ")");
            }
            previous = entry;
        }
        int last = previous;
        if (tailBp < 0 || tailBp > FULL_BP) {
            return DataResult.error(() -> tailField + " must be 0.." + FULL_BP + ", got " + tailBp);
        }
        if (tailBp > last) {
            return DataResult.error(() -> tailField + " (" + tailBp + ") must not exceed the last "
                    + "schedule entry (" + last + "); the tail continues the schedule rather than "
                    + "restarting it");
        }
        return DataResult.success(schedule);
    }

    /** The percentage for a zero-based group occurrence ordinal. Occurrence 1 is ordinal {@code 0}. */
    public int bpForOrdinal(int ordinal) {
        return scheduleBp(creditScheduleBp, tailBp, ordinal);
    }

    /**
     * Schedule lookup, shared by the group and subject ladders. A negative ordinal cannot happen from
     * a tracker but is treated as occurrence 1 rather than throwing, since the alternative inside a
     * staged transaction is an exception on a path that has already accepted the deed.
     */
    static int scheduleBp(List<Integer> schedule, int tailBp, int ordinal) {
        if (schedule.isEmpty()) {
            return tailBp;
        }
        if (ordinal <= 0) {
            return schedule.get(0);
        }
        return ordinal < schedule.size() ? schedule.get(ordinal) : tailBp;
    }

    /** Whether this policy ever stops discounting, i.e. whether continued repetition still pays. */
    public boolean permitsIndefiniteCredit() {
        return tailBp > 0;
    }
}
