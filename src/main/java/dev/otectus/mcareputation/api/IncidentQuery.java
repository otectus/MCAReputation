package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.incident.IncidentStatus;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A selector over a player's incident history (spec §29.6, §30.2).
 *
 * <p>Used by the {@code mcareputation:has_incident} / {@code incident_status} quest conditions, by
 * Conversations' {@code conversations_reputation_incident} dialogue condition, and by the
 * {@code resolve_incident} quest reward.
 *
 * <p><b>Bounded, server-resolved, deterministic</b> (§29.6). A selector never lets a client pick which
 * incident to resolve: the server evaluates the whole thing, and where several match it takes the
 * newest by creation time with the incident UUID as a tiebreak, so the same selector against the same
 * ledger always picks the same record. An empty selector matches everything, which is why
 * {@code resolve_incident} additionally requires at least one narrowing field.
 */
public record IncidentQuery(
        Set<ResourceLocation> types,
        Set<IncidentStatus> statuses,
        Set<String> tags,
        boolean knownToSpeaker,
        long maxAgeTicks,
        boolean newestOnly) {

    public static final IncidentQuery ANY = new IncidentQuery(Set.of(), Set.of(), Set.of(), false, 0L, true);

    public IncidentQuery {
        types = types == null ? Set.of() : Set.copyOf(types);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        tags = tags == null ? Set.of()
                : Set.copyOf(tags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
    }

    /**
     * True when this selector narrows nothing at all. {@code knownToSpeaker} counts as narrowing:
     * a selector asking only for "incidents this villager knows" is a real constraint, even though
     * {@link #matches} cannot evaluate it without a speaker and the caller must.
     */
    public boolean isEmpty() {
        return types.isEmpty() && statuses.isEmpty() && tags.isEmpty() && maxAgeTicks <= 0L
                && !knownToSpeaker;
    }

    /**
     * Whether one incident satisfies the non-speaker-dependent parts of this query. Awareness
     * ({@link #knownToSpeaker}) is evaluated separately because it needs a specific villager, which
     * a view alone does not carry.
     */
    public boolean matches(ReputationIncidentView view) {
        if (view == null) {
            return false;
        }
        if (!types.isEmpty() && !types.contains(view.type())) {
            return false;
        }
        if (!statuses.isEmpty() && !statuses.contains(view.status())) {
            return false;
        }
        if (!tags.isEmpty() && tags.stream().noneMatch(view::hasTag)) {
            return false;
        }
        return maxAgeTicks <= 0L || view.ageTicks() <= maxAgeTicks;
    }

    /**
     * The matching incidents in deterministic order — newest first, incident id as tiebreak — so two
     * evaluations of the same selector against the same ledger can never disagree (§29.6).
     */
    public List<ReputationIncidentView> select(List<ReputationIncidentView> candidates) {
        List<ReputationIncidentView> matched = candidates.stream()
                .filter(this::matches)
                .sorted(java.util.Comparator
                        .comparingLong(ReputationIncidentView::createdGameTime).reversed()
                        .thenComparing(view -> view.id().toString()))
                .toList();
        return newestOnly && !matched.isEmpty() ? List.of(matched.get(0)) : matched;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Set<ResourceLocation> types = new java.util.LinkedHashSet<>();
        private final Set<IncidentStatus> statuses = new java.util.LinkedHashSet<>();
        private final Set<String> tags = new java.util.LinkedHashSet<>();
        private boolean knownToSpeaker;
        private long maxAgeTicks;
        private boolean newestOnly = true;

        public Builder type(ResourceLocation type) {
            if (type != null) {
                types.add(type);
            }
            return this;
        }

        public Builder types(Iterable<ResourceLocation> values) {
            if (values != null) {
                values.forEach(this::type);
            }
            return this;
        }

        public Builder status(IncidentStatus status) {
            if (status != null) {
                statuses.add(status);
            }
            return this;
        }

        public Builder statuses(Iterable<IncidentStatus> values) {
            if (values != null) {
                values.forEach(this::status);
            }
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank()) {
                tags.add(tag);
            }
            return this;
        }

        public Builder tags(Iterable<String> values) {
            if (values != null) {
                values.forEach(this::tag);
            }
            return this;
        }

        public Builder knownToSpeaker(boolean value) {
            this.knownToSpeaker = value;
            return this;
        }

        public Builder maxAgeTicks(long value) {
            this.maxAgeTicks = Math.max(0L, value);
            return this;
        }

        public Builder newestOnly(boolean value) {
            this.newestOnly = value;
            return this;
        }

        public IncidentQuery build() {
            return new IncidentQuery(types, statuses, tags, knownToSpeaker, maxAgeTicks, newestOnly);
        }
    }
}
