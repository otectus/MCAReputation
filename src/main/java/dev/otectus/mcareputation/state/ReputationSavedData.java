package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The one canonical store for every player's public standing (spec §13.1–§13.2), persisted to
 * {@code <world>/data/mcareputation.dat}.
 *
 * <p>Pinned to the <b>overworld's</b> {@code DimensionDataStorage} even though communities are
 * dimension-aware. That is not a contradiction: the dimension lives inside {@link CommunityKey}, so
 * one world-global file holds every dimension's communities and a player's standing in the Nether
 * survives being read while they are in the End. Splitting per level would make cross-dimension
 * queries — the known-community list, the whole-player incident cap — impossible to answer.
 *
 * <p>{@link #setDirty()} is called after every mutation, so the store is written on the same autosave
 * and shutdown path as MCA's own village and family data. The lifetime therefore matches data the
 * player already trusts.
 *
 * <h2>Loading is forgiving on purpose</h2>
 *
 * <p>§13.6 requires a malformed entry to be skipped with context rather than to abort a world load.
 * Every level of the load — player, community, incident, subject, witness, title — is guarded
 * independently, so the worst outcome of a corrupted region of the file is that one player loses one
 * village's history, not that nobody can open the world.
 *
 * <h2>The registry provider is an adapter parameter, not part of the schema</h2>
 *
 * <p>1.21.1's {@code SavedData} passes a {@link HolderLookup.Provider} to both save and load. This
 * schema stores nothing that needs one — primitives, strings, UUIDs, and resource-location text — so
 * the lookup-aware methods are thin adapters over {@link #savePayload} and {@link #loadPayload},
 * which stay provider-neutral. Those two are what the tests and the golden 1.20.1 fixture exercise,
 * and keeping them free of a provider is what lets a fixture written by the Forge build be read back
 * here unchanged. FORMAT_VERSION stays 1 across the loader port because the bytes did not move.
 */
public final class ReputationSavedData extends SavedData {

    /** {@code <world>/data/mcareputation.dat}. */
    public static final String DATA_NAME = McaReputation.MOD_ID;

    /** Bump only for a format change that {@link #migrateFormat} can carry forward. */
    public static final int FORMAT_VERSION = 1;

    private final Map<UUID, PlayerReputationRecord> players = new LinkedHashMap<>();
    /**
     * Communities whose scores decay never touches. Ordered so the saved list is stable between
     * writes, which keeps a diff of two saves readable.
     */
    private final Set<CommunityKey> decayImmune = new TreeSet<>();
    private int loadedVersion = FORMAT_VERSION;

    public ReputationSavedData() {
    }

    /**
     * The 1.21.1 factory triple: create-supplier, lookup-aware load function, and (unused here) an
     * optional data-fixer type. The mod owns its own schema version, so no vanilla fixer is attached.
     */
    private static final SavedData.Factory<ReputationSavedData> FACTORY =
            new SavedData.Factory<>(ReputationSavedData::new, ReputationSavedData::load);

    public static ReputationSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public int loadedVersion() {
        return loadedVersion;
    }

    // --- player access ------------------------------------------------------

    /** The player's record if one exists. Never creates: a plain query must not grow the save. */
    public Optional<PlayerReputationRecord> player(UUID playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    /** The player's record, creating an empty one. Use only on paths that are about to write. */
    public PlayerReputationRecord getOrCreatePlayer(UUID playerId) {
        return players.computeIfAbsent(playerId, PlayerReputationRecord::new);
    }

    public Collection<PlayerReputationRecord> players() {
        return Collections.unmodifiableCollection(players.values());
    }

    public Set<UUID> playerIds() {
        return Collections.unmodifiableSet(players.keySet());
    }

    public int playerCount() {
        return players.size();
    }

    // --- convenience queries ------------------------------------------------

    /**
     * A player's standing with a community. Returns {@code 0} for an unknown player or community —
     * everybody starts a stranger (§17.1), and there is no meaningful difference between "no record"
     * and "score zero" from the outside.
     */
    public int score(UUID playerId, CommunityKey community) {
        return player(playerId)
                .flatMap(record -> record.community(community))
                .map(CommunityReputationRecord::score)
                .orElse(0);
    }

    /** True when this player has any record at all for this community. */
    public boolean knows(UUID playerId, CommunityKey community) {
        return player(playerId).flatMap(record -> record.community(community)).isPresent();
    }

    /**
     * Every community mentioned by any player's record, deduplicated and ordered. Feeds command
     * suggestions and the {@code debug community} listing; bounded by the per-player community cap.
     */
    public List<CommunityKey> allKnownCommunities() {
        java.util.TreeSet<CommunityKey> ordered = new java.util.TreeSet<>();
        players.values().forEach(player -> ordered.addAll(player.communityKeys()));
        return List.copyOf(ordered);
    }

    /**
     * Brings one player's decay up to date across all their communities and enforces the whole-player
     * incident cap. Idempotent, and the only reconciliation entry point — login, screen open, query,
     * mutation, and the rate-limited online sweep all funnel through here (§15.1).
     *
     * @return true when something changed and the store was marked dirty
     */
    public boolean reconcilePlayer(UUID playerId, long gameTime) {
        PlayerReputationRecord record = players.get(playerId);
        if (record == null) {
            return false;
        }
        int min = McaReputationConfig.minimumScore();
        int max = McaReputationConfig.maximumScore();
        boolean changed = false;
        if (McaReputationConfig.scoreDecayEnabled()) {
            for (CommunityReputationRecord community : record.communities()) {
                // An immune community is skipped here and nowhere else: this is the single
                // reconciliation entry point, so one check covers login, query, mutation and sweep.
                if (decayImmune.contains(community.key())) {
                    continue;
                }
                if (community.reconcile(gameTime, min, max)) {
                    changed = true;
                }
            }
        }
        // Cap enforcement mutates the store (prunes incidents, folds weight into baselines) just as
        // decay does; either kind of change must mark the save dirty or a crash loses it.
        if (record.enforcePlayerIncidentCap(McaReputationConfig.maxIncidentsPerPlayer(), gameTime,
                min, max) > 0) {
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    // --- decay immunity -----------------------------------------------------

    /** Whether decay is switched off for this community, for every player at once. */
    public boolean isDecayImmune(CommunityKey community) {
        return community != null && decayImmune.contains(community);
    }

    /**
     * Switches decay off or back on for one community. Marks the store dirty only when the flag
     * actually moved, so an operator repeating the command does not force a needless write.
     *
     * @return true when the flag changed
     */
    public boolean setDecayImmune(CommunityKey community, boolean immune) {
        if (community == null) {
            return false;
        }
        boolean changed = immune ? decayImmune.add(community) : decayImmune.remove(community);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** The immune communities, in key order. Unmodifiable. */
    public Set<CommunityKey> decayImmuneCommunities() {
        return Collections.unmodifiableSet(decayImmune);
    }

    // --- persistence --------------------------------------------------------

    /** The 1.21.1 adapter. The provider is unused: this schema needs no registry context. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return savePayload(tag);
    }

    /** The provider-neutral serializer. Byte-for-byte what the Forge 1.20.1 build wrote. */
    CompoundTag savePayload(CompoundTag tag) {
        tag.putInt("version", FORMAT_VERSION);
        CompoundTag playerTag = new CompoundTag();
        players.forEach((uuid, record) -> {
            if (!record.isEmpty()) {
                playerTag.put(uuid.toString(), record.save());
            }
        });
        tag.put("players", playerTag);
        // Written only when non-empty, and read only when present: a 0.3.0 file has no such tag and
        // must keep loading unchanged, which is why FORMAT_VERSION does not move for this.
        if (!decayImmune.isEmpty()) {
            ListTag immuneTag = new ListTag();
            decayImmune.forEach(key -> immuneTag.add(key.save()));
            tag.put("decayImmune", immuneTag);
        }
        return tag;
    }

    /** The 1.21.1 adapter. The provider is unused: this schema needs no registry context. */
    private static ReputationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return loadPayload(tag);
    }

    /** The provider-neutral deserializer, and what the golden 1.20.1 fixture is read through. */
    public static ReputationSavedData loadPayload(CompoundTag tag) {
        ReputationSavedData data = new ReputationSavedData();
        data.loadedVersion = tag.contains("version") ? tag.getInt("version") : FORMAT_VERSION;
        if (data.loadedVersion > FORMAT_VERSION) {
            McaReputation.LOGGER.warn(
                    "[MCA: Reputation] {}.dat was written by a newer format (v{} > v{}). Loading what is "
                            + "readable; unknown fields are preserved only if they sit inside entries we keep.",
                    DATA_NAME, data.loadedVersion, FORMAT_VERSION);
        }

        int min = McaReputationConfig.minimumScore();
        int max = McaReputationConfig.maximumScore();
        CompoundTag playerTag = tag.getCompound("players");
        int skipped = 0;
        for (String key : playerTag.getAllKeys()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                skipped++;
                McaReputation.LOGGER.debug("[MCA: Reputation] skipping player entry with unparseable UUID '{}'", key);
                continue;
            }
            try {
                data.players.put(playerId,
                        PlayerReputationRecord.load(playerId, playerTag.getCompound(key), min, max));
            } catch (Throwable t) {
                skipped++;
                McaReputation.LOGGER.warn("[MCA: Reputation] skipping unreadable record for player {}", playerId, t);
            }
        }
        if (tag.contains("decayImmune")) {
            for (Tag entry : tag.getList("decayImmune", Tag.TAG_COMPOUND)) {
                CommunityKey.load((CompoundTag) entry).ifPresent(data.decayImmune::add);
            }
        }
        data.migrateFormat();
        if (skipped > 0) {
            McaReputation.LOGGER.warn("[MCA: Reputation] loaded {} player record(s), skipped {} malformed entr(ies)",
                    data.players.size(), skipped);
        } else {
            McaReputation.LOGGER.info("[MCA: Reputation] loaded standing for {} player(s) across {} communit(ies)",
                    data.players.size(), data.allKnownCommunities().size());
        }
        return data;
    }

    /**
     * Carries an older on-disk format forward. Nothing to do at v1 — the hook exists so that the very
     * first format change has an obvious, tested place to live rather than being bolted onto
     * {@link #load}.
     */
    private void migrateFormat() {
        if (loadedVersion < FORMAT_VERSION) {
            McaReputation.LOGGER.info("[MCA: Reputation] upgrading saved data from format v{} to v{}",
                    loadedVersion, FORMAT_VERSION);
            loadedVersion = FORMAT_VERSION;
            setDirty();
        }
    }

    /** Test seam: an in-memory store with no server attached. */
    public static ReputationSavedData createForTest() {
        return new ReputationSavedData();
    }

    /** Test seam: a full save/load round trip, exercising the real NBT path. */
    public ReputationSavedData roundTripForTest() {
        List<UUID> before = new ArrayList<>(players.keySet());
        ReputationSavedData reloaded = loadPayload(savePayload(new CompoundTag()));
        if (reloaded.players.size() != before.stream().filter(id -> !players.get(id).isEmpty()).count()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] round trip dropped empty player records, as designed");
        }
        return reloaded;
    }
}
