package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the server remembers about one player's public life (spec §13.3).
 *
 * <p>The per-player keying is the single most important structural difference from the system this
 * replaces. MCA: Quests stores reputation per <em>village</em>, world-shared, so on a multiplayer
 * server every player reads the same number even though the UI calls it "your standing". Here the
 * player is the outer key and the community the inner one, which is what makes §41's acceptance test —
 * "player A's deed never changes player B's score" — true by construction rather than by care.
 *
 * <h2>The dedupe index</h2>
 *
 * <p>{@link #dedupeIndex} is a <b>cache, never a source of truth</b>. It is rebuilt from the stored
 * incidents on load (§14.2) and bounded, so a corrupt or truncated index can cost at most a duplicate
 * rejection lookup, never a duplicate award: {@link #findByDedupeKey} falls back to scanning the
 * community's own ledger when the index misses.
 */
public final class PlayerReputationRecord {

    private final UUID playerId;
    private final Map<CommunityKey, CommunityReputationRecord> communities = new LinkedHashMap<>();
    private final Set<ResourceLocation> globalTitles = new LinkedHashSet<>();
    private final Map<String, String> migrationMarkers = new LinkedHashMap<>();

    /** {@code "<community>|<dedupeKey>"} → incident id. Rebuilt on load; bounded; advisory only. */
    private final Map<String, UUID> dedupeIndex = new LinkedHashMap<>();

    private String lastKnownName = "";

    public PlayerReputationRecord(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    /**
     * Diagnostic only (§13.3). Never used for identity, matching, or display to other players — a
     * name can change, a UUID cannot, and this exists purely so an administrator reading a save or a
     * log can tell whose record they are looking at.
     */
    public String lastKnownName() {
        return lastKnownName;
    }

    public void setLastKnownName(String name) {
        if (name != null && !name.isBlank()) {
            this.lastKnownName = name.length() <= 64 ? name : name.substring(0, 64);
        }
    }

    public boolean isEmpty() {
        return communities.values().stream().allMatch(CommunityReputationRecord::isEmpty)
                && globalTitles.isEmpty() && migrationMarkers.isEmpty();
    }

    // --- communities --------------------------------------------------------

    public Optional<CommunityReputationRecord> community(CommunityKey key) {
        return Optional.ofNullable(communities.get(key));
    }

    /**
     * The record for this community, creating it if absent. Creation is what makes a community
     * "known" to the player, so this is called on write paths and on explicit metadata caching, but
     * <em>not</em> on a plain score query — reading a stranger's standing must not populate the save
     * with an empty record for every village the player walks past.
     */
    public CommunityReputationRecord getOrCreate(CommunityKey key) {
        CommunityReputationRecord existing = communities.get(key);
        if (existing != null) {
            return existing;
        }
        if (communities.size() >= ReputationBounds.MAX_COMMUNITIES_PER_PLAYER) {
            evictLeastInteresting();
        }
        CommunityReputationRecord created = new CommunityReputationRecord(key);
        communities.put(key, created);
        return created;
    }

    /**
     * Drops the oldest empty community record to make room. Only genuinely empty records are eligible,
     * so hitting the cap can never cost a player standing; if none are empty the cap is exceeded and
     * logged rather than something meaningful being discarded.
     */
    private void evictLeastInteresting() {
        for (Map.Entry<CommunityKey, CommunityReputationRecord> entry : communities.entrySet()) {
            if (entry.getValue().isEmpty()) {
                communities.remove(entry.getKey());
                return;
            }
        }
        McaReputation.LOGGER.warn(
                "[MCA: Reputation] player {} tracks {} communities, above the cap of {}; none are empty so "
                        + "nothing was evicted",
                playerId, communities.size(), ReputationBounds.MAX_COMMUNITIES_PER_PLAYER);
    }

    public Collection<CommunityReputationRecord> communities() {
        return Collections.unmodifiableCollection(communities.values());
    }

    public Set<CommunityKey> communityKeys() {
        return Collections.unmodifiableSet(communities.keySet());
    }

    /** Known communities ordered for display: best standing first, then by key for stability. */
    public List<CommunityReputationRecord> communitiesByStanding() {
        List<CommunityReputationRecord> ordered = new ArrayList<>(communities.values());
        ordered.sort(CommunityReputationRecord.BY_STANDING);
        return ordered;
    }

    // --- global titles ------------------------------------------------------

    public Set<ResourceLocation> globalTitles() {
        return Collections.unmodifiableSet(globalTitles);
    }

    public boolean hasGlobalTitle(ResourceLocation title) {
        return globalTitles.contains(title);
    }

    /** @return true when newly added. */
    public boolean grantGlobalTitle(ResourceLocation title) {
        if (title == null || globalTitles.size() >= ReputationBounds.MAX_TITLES) {
            return false;
        }
        return globalTitles.add(title);
    }

    public boolean revokeGlobalTitle(ResourceLocation title) {
        return globalTitles.remove(title);
    }

    // --- migration markers --------------------------------------------------

    /**
     * Records that a one-time import from another system has completed for this player. §32.2 hangs
     * the entire "never repeat or add legacy values again" guarantee on this marker, which is why it
     * is written only <em>after</em> the canonical save has successfully mutated.
     */
    public void markMigrated(String sourceId, String version) {
        if (sourceId != null && !sourceId.isBlank()) {
            migrationMarkers.put(sourceId, version == null ? "1" : version);
        }
    }

    public boolean hasMigrated(String sourceId) {
        return migrationMarkers.containsKey(sourceId);
    }

    public Optional<String> migrationVersion(String sourceId) {
        return Optional.ofNullable(migrationMarkers.get(sourceId));
    }

    public Map<String, String> migrationMarkers() {
        return Collections.unmodifiableMap(migrationMarkers);
    }

    // --- dedupe -------------------------------------------------------------

    private static String dedupeIndexKey(CommunityKey community, String dedupeKey) {
        return community.asString() + "|" + dedupeKey;
    }

    /**
     * The incident already recorded under this dedupe key for this community, if any.
     *
     * <p>Consults the index first, then falls back to scanning the community's ledger. The fallback is
     * what makes the guarantee hold across a save/load cycle even if the index were somehow lost: the
     * records themselves carry their dedupe keys, so they remain the authority.
     */
    public Optional<IncidentRecord> findByDedupeKey(CommunityKey community, String dedupeKey) {
        if (community == null || dedupeKey == null || dedupeKey.isBlank()) {
            return Optional.empty();
        }
        CommunityReputationRecord record = communities.get(community);
        if (record == null) {
            return Optional.empty();
        }
        UUID indexed = dedupeIndex.get(dedupeIndexKey(community, dedupeKey));
        if (indexed != null) {
            Optional<IncidentRecord> hit = record.incident(indexed);
            if (hit.isPresent()) {
                return hit;
            }
            dedupeIndex.remove(dedupeIndexKey(community, dedupeKey)); // stale entry, e.g. after pruning
        }
        return record.incidents().stream()
                .filter(incident -> incident.dedupeKey().map(dedupeKey::equals).orElse(false))
                .findFirst();
    }

    public void indexDedupe(IncidentRecord incident) {
        incident.dedupeKey().ifPresent(key -> {
            if (dedupeIndex.size() >= ReputationBounds.MAX_DEDUPE_INDEX_PER_PLAYER) {
                // Oldest-first eviction; the ledger fallback keeps correctness regardless.
                java.util.Iterator<String> it = dedupeIndex.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            dedupeIndex.put(dedupeIndexKey(incident.community(), key), incident.id());
        });
    }

    /** Rebuilds the whole index from the stored records (§14.2). Called after every load. */
    public void rebuildDedupeIndex() {
        dedupeIndex.clear();
        for (CommunityReputationRecord community : communities.values()) {
            for (IncidentRecord incident : community.incidents()) {
                indexDedupe(incident);
            }
        }
    }

    // --- cross-community bounds ---------------------------------------------

    public int totalIncidentCount() {
        int total = 0;
        for (CommunityReputationRecord community : communities.values()) {
            total += community.incidentCount();
        }
        return total;
    }

    /**
     * Enforces the whole-player incident cap (§13.5) by pruning the fullest community first, using the
     * same priority order and the same fold-into-baseline guarantee as the per-community pass.
     * Iterating fullest-first means a player who is deeply involved in one village does not lose the
     * only two incidents they have somewhere else.
     *
     * @return how many incidents were pruned; {@code 0} means the store did not change
     */
    public int enforcePlayerIncidentCap(int maxPerPlayer, long gameTime, int minScore, int maxScore) {
        int total = totalIncidentCount();
        if (total <= maxPerPlayer) {
            return 0;
        }
        int prunedTotal = 0;
        List<CommunityReputationRecord> fullestFirst = new ArrayList<>(communities.values());
        fullestFirst.sort(java.util.Comparator.comparingInt(CommunityReputationRecord::incidentCount).reversed());
        for (CommunityReputationRecord community : fullestFirst) {
            if (total <= maxPerPlayer) {
                break;
            }
            int target = Math.max(1, community.incidentCount() - (total - maxPerPlayer));
            List<IncidentRecord> removed = community.prune(target, gameTime, minScore, maxScore);
            total -= removed.size();
            prunedTotal += removed.size();
            // A community that yields nothing (everything pinned) must not end the sweep: the
            // next-fullest community may still have prunable history, and skipping it would leave the
            // whole player permanently over cap because of one stubborn village.
            // (The unprunable community has already logged its own warning.)
        }
        if (prunedTotal > 0) {
            rebuildDedupeIndex();
        }
        return prunedTotal;
    }

    // --- persistence --------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (!lastKnownName.isEmpty()) {
            tag.putString("name", lastKnownName);
        }
        ListTag communityList = new ListTag();
        communities.values().forEach(community -> communityList.add(community.save()));
        tag.put("communities", communityList);

        if (!globalTitles.isEmpty()) {
            ListTag titleList = new ListTag();
            globalTitles.forEach(title -> titleList.add(StringTag.valueOf(title.toString())));
            tag.put("globalTitles", titleList);
        }
        if (!migrationMarkers.isEmpty()) {
            CompoundTag markers = new CompoundTag();
            migrationMarkers.forEach(markers::putString);
            tag.put("migrations", markers);
        }
        return tag;
    }

    /** Per-entry guarded (§13.6): one bad community never costs the player their other villages. */
    public static PlayerReputationRecord load(UUID playerId, CompoundTag tag, int minScore, int maxScore) {
        PlayerReputationRecord record = new PlayerReputationRecord(playerId);
        record.setLastKnownName(tag.getString("name"));

        ListTag communityList = tag.getList("communities", Tag.TAG_COMPOUND);
        for (int i = 0; i < communityList.size(); i++) {
            try {
                CommunityReputationRecord.load(communityList.getCompound(i), minScore, maxScore)
                        .ifPresent(community -> record.communities.put(community.key(), community));
            } catch (Throwable t) {
                McaReputation.LOGGER.debug("[MCA: Reputation] skipping community that threw while loading for {}",
                        playerId, t);
            }
        }

        ListTag titleList = tag.getList("globalTitles", Tag.TAG_STRING);
        for (int i = 0; i < titleList.size() && record.globalTitles.size() < ReputationBounds.MAX_TITLES; i++) {
            ResourceLocation title = ResourceLocation.tryParse(titleList.getString(i));
            if (title != null) {
                record.globalTitles.add(title);
            }
        }

        CompoundTag markers = tag.getCompound("migrations");
        for (String key : markers.getAllKeys()) {
            record.migrationMarkers.put(key, markers.getString(key));
        }

        record.rebuildDedupeIndex();
        return record;
    }

    /** Diagnostic snapshot for {@code /mcareputation debug}. */
    public Map<String, Integer> scoreByCommunity() {
        Map<String, Integer> out = new HashMap<>();
        communities.forEach((key, record) -> out.put(key.asString(), record.score()));
        return out;
    }
}
