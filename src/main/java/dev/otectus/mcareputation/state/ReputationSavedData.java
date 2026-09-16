package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.reputation.ReconciliationService;
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
 */
public final class ReputationSavedData extends SavedData {

    /** {@code <world>/data/mcareputation.dat}. */
    public static final String DATA_NAME = McaReputation.MOD_ID;

    /** Bump only for a format change that {@link #migrateFormat} can carry forward. */
    public static final int FORMAT_VERSION = 2;

    private final Map<UUID, PlayerReputationRecord> players = new LinkedHashMap<>();
    /**
     * Communities whose scores decay never touches. Ordered so the saved list is stable between
     * writes, which keeps a diff of two saves readable.
     */
    private final Set<CommunityKey> decayImmune = new TreeSet<>();
    private int loadedVersion = FORMAT_VERSION;

    /**
     * Set when the file on disk was written by a newer format than this build understands. Nothing is
     * loaded into live state, {@link #setDirty} is inert, and {@link #save} writes the untouched tag
     * back: preserving a file we cannot read beats converting it destructively (§9).
     */
    private boolean readOnly;
    private CompoundTag retainedRaw;

    public ReputationSavedData() {
    }

    public static ReputationSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage()
                .computeIfAbsent(ReputationSavedData::load, ReputationSavedData::new, DATA_NAME);
    }

    public int loadedVersion() {
        return loadedVersion;
    }

    /**
     * True when this store is latched read-only because the file came from a newer format. Every
     * mutation is still accepted by the API and simply never persisted, so a server stays playable
     * while an operator downgrades or restores a backup.
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void setDirty(boolean value) {
        if (readOnly) {
            return;
        }
        super.setDirty(value);
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
        // The freeze decision lives in the gate, not here: immunity used to be checked in this method
        // and nowhere else, which is exactly why seven service paths could walk round it (§5 F07).
        return ReconciliationService.reconcilePlayer(McaReputationConfig.snapshot(), this, playerId,
                gameTime, (community, outcome) -> {
                });
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

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (readOnly && retainedRaw != null) {
            // Verbatim, key for key: the one safe thing to do with a file from the future is to hand
            // it back exactly as it arrived.
            for (String key : retainedRaw.getAllKeys()) {
                tag.put(key, retainedRaw.get(key).copy());
            }
            return tag;
        }
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

    public static ReputationSavedData load(CompoundTag tag) {
        ReputationSavedData data = new ReputationSavedData();
        // A file with no version at all predates versioning: it is format 1, not "whatever this build
        // happens to be", or the migration below would never run on the oldest saves of all.
        data.loadedVersion = tag.contains("version") ? tag.getInt("version") : 1;
        if (data.loadedVersion > FORMAT_VERSION) {
            data.readOnly = true;
            data.retainedRaw = tag.copy();
            McaReputation.LOGGER.error(
                    "[MCA: Reputation] {}.dat was written by format v{}, and this build understands v{}. "
                            + "Nothing has been loaded and nothing will be written: the file is preserved "
                            + "exactly as it is. Run the newer version of MCA: Reputation, or restore a "
                            + "backup taken before the upgrade.",
                    DATA_NAME, data.loadedVersion, FORMAT_VERSION);
            return data;
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
                McaReputation.LOGGER.debug("[MCA: Reputation] quarantining player entry with unparseable "
                        + "UUID '{}'", key);
                SaveQuarantine.hold("players/" + key, "unparseable player UUID", playerTag.getCompound(key));
                continue;
            }
            try {
                data.players.put(playerId,
                        PlayerReputationRecord.load(playerId, playerTag.getCompound(key), min, max));
            } catch (Throwable t) {
                skipped++;
                McaReputation.LOGGER.warn("[MCA: Reputation] quarantining unreadable record for player {}",
                        playerId, t);
                SaveQuarantine.hold("players/" + key, "player record threw while loading: " + t,
                        playerTag.getCompound(key));
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
     * Carries an older on-disk format forward (§9). Idempotent, silent, and lossless: it emits no
     * event, no toast, no mirror call and no reward, invents nothing for history that was already
     * pruned, and never moves a score — every field it writes is one that was already implied by what
     * is on disk.
     *
     * <h2>v1 to v2</h2>
     *
     * <ol>
     *   <li>Every retained incident with a dedupe key gets an {@code APPLIED} receipt returning that
     *       incident's id, so a companion replaying a pre-upgrade operation key learns what it already
     *       produced instead of recording it a second time.</li>
     *   <li>Every record carrying only the legacy {@code superseded_by} context entry becomes terminal,
     *       with the typed link parsed when it is a valid UUID.</li>
     * </ol>
     *
     * <p>There is deliberately <b>no</b> freeze clock in v2. The WP3 reconciliation gate already skips
     * an immune community's elapsed time on its first pass rather than banking it, which is what
     * "initialise the freeze clock at upgrade" was asking for; a second stored clock would be a field
     * whose only job is to agree with that one.
     */
    private void migrateFormat() {
        if (loadedVersion >= FORMAT_VERSION) {
            return;
        }
        int receipts = 0;
        int terminal = 0;
        for (PlayerReputationRecord player : players.values()) {
            for (CommunityReputationRecord community : player.communities()) {
                for (IncidentRecord incident : community.incidents()) {
                    Optional<String> key = incident.dedupeKey();
                    if (key.isPresent()) {
                        String namespace = incident.source().getNamespace();
                        if (player.findReceipt(namespace, community.key(), key.get()).isEmpty()) {
                            player.recordReceipt(new OperationReceipt(namespace, player.playerId(),
                                    community.key(), key.get(), ReceiptOutcome.APPLIED,
                                    Optional.of(incident.id()), incident.createdGameTime(),
                                    incident.appliedGameTime()));
                            receipts++;
                        }
                    }
                    if (incident.adoptLegacySupersession()) {
                        terminal++;
                    }
                }
            }
        }
        McaReputation.LOGGER.info("[MCA: Reputation] upgrading saved data from format v{} to v{}: "
                        + "{} receipt(s) recovered from retained incidents, {} record(s) marked terminal",
                loadedVersion, FORMAT_VERSION, receipts, terminal);
        loadedVersion = FORMAT_VERSION;
        setDirty();
    }

    /** Test seam: an in-memory store with no server attached. */
    public static ReputationSavedData createForTest() {
        return new ReputationSavedData();
    }

    /** Test seam: a full save/load round trip, exercising the real NBT path. */
    public ReputationSavedData roundTripForTest() {
        List<UUID> before = new ArrayList<>(players.keySet());
        ReputationSavedData reloaded = load(save(new CompoundTag()));
        if (reloaded.players.size() != before.stream().filter(id -> !players.get(id).isEmpty()).count()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] round trip dropped empty player records, as designed");
        }
        return reloaded;
    }
}
