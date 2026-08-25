package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A one-time import of standing that predates this mod (spec §25, §32.2).
 *
 * <p>The only current caller is MCA: Quests, whose reputation was stored per <em>village</em> and
 * shared by the whole world. Those numbers cannot be decomposed into individual histories — the
 * information simply was not recorded — so the import deliberately does <b>not</b> fabricate deeds.
 * Each legacy score becomes a {@link #baselines} entry, which is non-decaying standing with no
 * incident attached, and the ledger honestly starts empty. That preserves the number the player used
 * to see while making everything from here on correct.
 *
 * <p>{@link #sourceId} is the migration marker. Once an import completes, that marker is written to
 * the player's record and the same source can never be applied again, no matter how often the trigger
 * fires (§32.2 step 9).
 */
public record LegacyImportRequest(
        MinecraftServer server,
        UUID playerId,
        String sourceId,
        String sourceVersion,
        Map<CommunityKey, Integer> baselines,
        Map<CommunityKey, Map<ResourceLocation, String>> tierHighWater,
        Map<CommunityKey, Set<ResourceLocation>> villageTitles,
        Set<ResourceLocation> globalTitles,
        Map<CommunityKey, String> communityNames,
        boolean dryRun) {

    public LegacyImportRequest {
        // The server may be null only for a request executed against an explicit service context —
        // the seam the transaction unit tests run through. Every public builder path requires one.
        if (playerId == null || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("LegacyImportRequest requires a player and source id");
        }
        sourceVersion = sourceVersion == null || sourceVersion.isBlank() ? "1" : sourceVersion;
        baselines = baselines == null ? Map.of() : Map.copyOf(baselines);
        tierHighWater = tierHighWater == null ? Map.of() : Map.copyOf(tierHighWater);
        villageTitles = villageTitles == null ? Map.of() : Map.copyOf(villageTitles);
        globalTitles = globalTitles == null ? Set.of() : Set.copyOf(globalTitles);
        communityNames = communityNames == null ? Map.of() : Map.copyOf(communityNames);
    }

    public boolean hasAnything() {
        return !baselines.isEmpty() || !tierHighWater.isEmpty()
                || !villageTitles.isEmpty() || !globalTitles.isEmpty();
    }

    public List<CommunityKey> communities() {
        return List.copyOf(baselines.keySet());
    }

    public Optional<String> communityName(CommunityKey key) {
        return Optional.ofNullable(communityNames.get(key)).filter(name -> !name.isBlank());
    }

    public static Builder builder(MinecraftServer server, UUID playerId, String sourceId) {
        if (server == null) {
            throw new IllegalArgumentException("LegacyImportRequest requires a server");
        }
        return new Builder(server, playerId, sourceId);
    }

    public static final class Builder {

        private final MinecraftServer server;
        private final UUID playerId;
        private final String sourceId;
        private String sourceVersion = "1";
        private final Map<CommunityKey, Integer> baselines = new java.util.LinkedHashMap<>();
        private final Map<CommunityKey, Map<ResourceLocation, String>> tierHighWater = new java.util.LinkedHashMap<>();
        private final Map<CommunityKey, Set<ResourceLocation>> villageTitles = new java.util.LinkedHashMap<>();
        private final Set<ResourceLocation> globalTitles = new java.util.LinkedHashSet<>();
        private final Map<CommunityKey, String> communityNames = new java.util.LinkedHashMap<>();
        private boolean dryRun;

        private Builder(MinecraftServer server, UUID playerId, String sourceId) {
            this.server = server;
            this.playerId = playerId;
            this.sourceId = sourceId;
        }

        public Builder version(String value) {
            this.sourceVersion = value;
            return this;
        }

        public Builder baseline(CommunityKey community, int score) {
            if (community != null) {
                baselines.merge(community, score, Integer::sum);
            }
            return this;
        }

        public Builder tierHighWater(CommunityKey community, ResourceLocation ladder, String tierId) {
            if (community != null && ladder != null && tierId != null && !tierId.isBlank()) {
                tierHighWater.computeIfAbsent(community, k -> new java.util.LinkedHashMap<>()).put(ladder, tierId);
            }
            return this;
        }

        public Builder villageTitle(CommunityKey community, ResourceLocation title) {
            if (community != null && title != null) {
                villageTitles.computeIfAbsent(community, k -> new java.util.LinkedHashSet<>()).add(title);
            }
            return this;
        }

        public Builder globalTitle(ResourceLocation title) {
            if (title != null) {
                globalTitles.add(title);
            }
            return this;
        }

        public Builder communityName(CommunityKey community, String name) {
            if (community != null && name != null && !name.isBlank()) {
                communityNames.put(community, name);
            }
            return this;
        }

        /** Report what would happen and write nothing — backs {@code /mcareputation migrate run --dry-run}. */
        public Builder dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }

        public LegacyImportRequest build() {
            Map<CommunityKey, Map<ResourceLocation, String>> frozenHighWater = new java.util.LinkedHashMap<>();
            tierHighWater.forEach((key, value) -> frozenHighWater.put(key, Map.copyOf(value)));
            Map<CommunityKey, Set<ResourceLocation>> frozenTitles = new java.util.LinkedHashMap<>();
            villageTitles.forEach((key, value) -> frozenTitles.put(key, Set.copyOf(value)));
            return new LegacyImportRequest(server, playerId, sourceId, sourceVersion,
                    Map.copyOf(baselines), Map.copyOf(frozenHighWater), Map.copyOf(frozenTitles),
                    Set.copyOf(globalTitles), Map.copyOf(communityNames), dryRun);
        }
    }
}
