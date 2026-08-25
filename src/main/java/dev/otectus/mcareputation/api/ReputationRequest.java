package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A request to record one deed (spec §18). This is the <b>only</b> way anything — a core damage hook,
 * a quest reward, a conversation action, a command, another mod — asks the canonical store to change.
 *
 * <p>Immutable and validated on construction, so a malformed request fails at the call site rather
 * than halfway through a transaction. Build one with {@link #builder}: the positional constructor has
 * a dozen parameters and call sites that use it are unreadable.
 *
 * <h2>On {@code dedupeKey}</h2>
 *
 * <p>Every integration-created request should carry one (§14.2). It is what makes a duplicated quest
 * turn-in packet, a doubled Forge event, a relog mid-transaction, or a datapack reload harmless: the
 * same key for the same player and community returns the earlier result and mutates nothing. The
 * recommended shapes are in §14.2; the only hard requirement is that the key is stable for one
 * logical outcome and different for different ones.
 */
public record ReputationRequest(
        MinecraftServer server,
        UUID playerId,
        CommunityKey community,
        ResourceLocation incidentType,
        ResourceLocation source,
        Optional<String> dedupeKey,
        OptionalInt deltaOverride,
        Optional<IncidentVisibility> visibilityOverride,
        List<IncidentSubject> subjects,
        Set<UUID> witnesses,
        Map<String, String> context,
        long gameTime) {

    public ReputationRequest {
        // The server may be null only for a request executed against an explicit service context —
        // the seam the transaction unit tests run through. Every public builder path requires one.
        if (playerId == null) {
            throw new IllegalArgumentException("ReputationRequest requires a player id");
        }
        if (community == null) {
            throw new IllegalArgumentException("ReputationRequest requires a community");
        }
        if (incidentType == null) {
            throw new IllegalArgumentException("ReputationRequest requires an incident type");
        }
        if (source == null) {
            throw new IllegalArgumentException("ReputationRequest requires a source id");
        }
        dedupeKey = dedupeKey == null ? Optional.empty() : dedupeKey;
        deltaOverride = deltaOverride == null ? OptionalInt.empty() : deltaOverride;
        visibilityOverride = visibilityOverride == null ? Optional.empty() : visibilityOverride;
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        witnesses = witnesses == null ? Set.of() : Set.copyOf(witnesses);
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static Builder builder(MinecraftServer server, UUID playerId, CommunityKey community,
                                  ResourceLocation incidentType, ResourceLocation source) {
        if (server == null) {
            throw new IllegalArgumentException("ReputationRequest requires a server");
        }
        return new Builder(server, playerId, community, incidentType, source);
    }

    /** Readable construction for the dozen-field request. Not thread safe; build one per call site. */
    public static final class Builder {

        private final MinecraftServer server;
        private final UUID playerId;
        private final CommunityKey community;
        private final ResourceLocation incidentType;
        private final ResourceLocation source;

        private Optional<String> dedupeKey = Optional.empty();
        private OptionalInt deltaOverride = OptionalInt.empty();
        private Optional<IncidentVisibility> visibilityOverride = Optional.empty();
        private final List<IncidentSubject> subjects = new java.util.ArrayList<>();
        private final Set<UUID> witnesses = new LinkedHashSet<>();
        private final Map<String, String> context = new LinkedHashMap<>();
        private long gameTime = Long.MIN_VALUE;

        private Builder(MinecraftServer server, UUID playerId, CommunityKey community,
                        ResourceLocation incidentType, ResourceLocation source) {
            this.server = server;
            this.playerId = playerId;
            this.community = community;
            this.incidentType = incidentType;
            this.source = source;
        }

        public Builder dedupeKey(String key) {
            this.dedupeKey = Optional.ofNullable(key).filter(k -> !k.isBlank());
            return this;
        }

        /** Clamped to the incident definition's {@code max_override_abs} when the request is applied. */
        public Builder delta(int delta) {
            this.deltaOverride = OptionalInt.of(delta);
            return this;
        }

        public Builder delta(OptionalInt delta) {
            this.deltaOverride = delta == null ? OptionalInt.empty() : delta;
            return this;
        }

        public Builder visibility(IncidentVisibility visibility) {
            this.visibilityOverride = Optional.ofNullable(visibility);
            return this;
        }

        public Builder subject(IncidentSubject subject) {
            if (subject != null) {
                subjects.add(subject);
            }
            return this;
        }

        public Builder subjects(List<IncidentSubject> values) {
            if (values != null) {
                subjects.addAll(values);
            }
            return this;
        }

        public Builder witness(UUID villager) {
            if (villager != null) {
                witnesses.add(villager);
            }
            return this;
        }

        public Builder witnesses(Iterable<UUID> values) {
            if (values != null) {
                values.forEach(this::witness);
            }
            return this;
        }

        public Builder context(String key, String value) {
            if (key != null && value != null) {
                context.put(key, value);
            }
            return this;
        }

        public Builder context(Map<String, String> values) {
            if (values != null) {
                context.putAll(values);
            }
            return this;
        }

        public Builder gameTime(long value) {
            this.gameTime = value;
            return this;
        }

        public ReputationRequest build() {
            long time = gameTime != Long.MIN_VALUE
                    ? gameTime
                    : server.overworld().getGameTime();
            return new ReputationRequest(server, playerId, community, incidentType, source, dedupeKey,
                    deltaOverride, visibilityOverride, List.copyOf(subjects), Set.copyOf(witnesses),
                    Map.copyOf(context), time);
        }
    }
}
