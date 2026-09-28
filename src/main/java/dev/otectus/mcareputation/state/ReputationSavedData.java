package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.StandingBaseline;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.LegacyEnrichmentManifest;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.reputation.ReconciliationService;
import net.minecraft.resources.ResourceLocation;
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
import java.io.File;
import java.io.IOException;

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
    public static final int FORMAT_VERSION = 4;

    /**
     * Players examined by one enrichment pass (§19.3). Small on purpose: the pass runs on the same
     * lazy triggers as reconciliation, so a big save finishes over several of them instead of
     * blocking one world load.
     */
    public static final int ENRICHMENT_PLAYER_BUDGET = 8;

    private final Map<UUID, PlayerReputationRecord> players = new LinkedHashMap<>();
    /**
     * Communities whose scores decay never touches. Ordered so the saved list is stable between
     * writes, which keeps a diff of two saves readable.
     */
    private final Set<CommunityKey> decayImmune = new TreeSet<>();
    /** How far §19.2's historical enrichment has got. Persisted; see {@link ProfileMigrationState}. */
    private ProfileMigrationState profileMigration = ProfileMigrationState.complete();
    /**
     * §12.2's bounded policy epochs for profile aging. Deliberately <b>not persisted</b>: game time
     * does not advance while the world is not running, so the intervals that need remembering are the
     * ones this JVM could have observed, and {@link ProfileFreezeLog} documents exactly what a restart
     * inside a freeze does instead of guessing.
     */
    private final ProfileFreezeLog profileFreeze = new ProfileFreezeLog();
    private int loadedVersion = FORMAT_VERSION;

    /**
     * Set when the file on disk was written by a newer format than this build understands. Nothing is
     * loaded into live state, {@link #setDirty} is inert, and {@link #save} writes the untouched tag
     * back: preserving a file we cannot read beats converting it destructively (§9).
     */
    private boolean readOnly;
    private CompoundTag retainedRaw;
    private StandingOutbox standingOutbox = new StandingOutbox();

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
     * The bounded policy epochs profile aging is measured against (§12.2).
     *
     * <p>Owned by the store because it is per-world state that outlives any one operation, and read by
     * {@code ReconciliationService} alone: a caller that wanted to subtract frozen intervals itself
     * would be a second aging path, which is the bypass the gate exists to prevent.
     */
    public ProfileFreezeLog profileFreezeLog() {
        return profileFreeze;
    }

    /**
     * True when this store is latched read-only because the file came from a newer format. Nothing
     * was loaded and nothing will be written, so a server stays playable — read-only — while an
     * operator downgrades or restores a backup.
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Whether a mutation may be attempted at all. Every write path checks this <em>before</em> it
     * changes anything (I14).
     *
     * <p>This used to be the opposite promise: mutations were accepted, applied in memory, and simply
     * never persisted. That is the worst of the available behaviours. A player's deed is recorded, the
     * toast fires, a producer stores the returned incident id as durable proof of settlement, and the
     * whole transaction evaporates on restart with no error anyone saw. An honest refusal — one a
     * producer can classify as retryable/degraded and an operator can read in the log — costs the same
     * deed and keeps every consumer's state consistent with what is actually on disk.
     */
    public boolean writable() {
        return !readOnly;
    }

    public StandingOutbox standingOutbox() {
        return standingOutbox;
    }

    /**
     * The player's record for a path that is about to write, or empty when this store may not be
     * written. Creates nothing on a read-only store, so a refused operation leaves no trace of having
     * been attempted.
     */
    public Optional<PlayerReputationRecord> getOrCreatePlayerIfWritable(UUID playerId) {
        return writable() ? Optional.of(getOrCreatePlayer(playerId)) : Optional.empty();
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

    /** Pure export for explicit migration previews; performs no reconciliation or record creation. */
    public List<StandingBaseline> standingBaselines() {
        return players.values().stream().flatMap(player -> player.communities().stream().map(community ->
                new StandingBaseline(player.playerId(), community.key(), community.score(),
                        community.revision()))).toList();
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
        if (!writable()) {
            // I14: this used to move the flag in memory, report success and never persist it. An
            // operator would see "immunity enabled", restart, and find the village ageing again with
            // no error anyone saw. Refusing is the only answer a read-only store can honestly give.
            McaReputation.LOGGER.warn("[MCA: Reputation] refused to {} decay immunity for {}: the saved "
                            + "data was written by a newer format, so this store is read-only",
                    immune ? "enable" : "disable", community.asString());
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
        return save(tag, standingOutbox.snapshotForSave(McaReputationConfig.standingJournalMaxEntries()));
    }

    private CompoundTag save(CompoundTag tag, StandingOutbox.Snapshot outboxSnapshot) {
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
        // Format 3. Written only when there is something to say, and written in the same tag as the
        // payloads it describes: setDirty is not a durable commit, so a pass whose progress never
        // reached disk has to be repeatable, and the two must never disagree about what was done.
        CompoundTag migration = profileMigration.save();
        if (!migration.isEmpty()) {
            tag.put("profileMigration", migration);
        }
        tag.put("standingOutbox", standingOutbox.save(outboxSnapshot));
        return tag;
    }

    @Override
    public void save(File file) {
        if (!isDirty()) return;
        StandingOutbox.Snapshot snapshot=standingOutbox.snapshotForSave(McaReputationConfig.standingJournalMaxEntries());
        try {
            DurableDataWriter.write(file,save(new CompoundTag(),snapshot));
            standingOutbox.markDurable(snapshot);
            setDirty(false);
        } catch(IOException exception) {
            McaReputation.LOGGER.error("[MCA: Reputation] could not durably save data {}",file,exception);
            setDirty(true);
        }
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
        data.profileMigration = ProfileMigrationState.load(tag.getCompound("profileMigration"));
        if(tag.contains("standingOutbox",Tag.TAG_COMPOUND)) {
            data.standingOutbox=StandingOutbox.load(tag.getCompound("standingOutbox"));
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
     * <p>The steps run in order, each from the version it upgrades, so a format-1 file arriving at a
     * format-3 build goes through both rather than skipping to the newest.
     */
    private void migrateFormat() {
        if (loadedVersion >= FORMAT_VERSION) {
            return;
        }
        if (loadedVersion < 2) {
            migrateV1ToV2();
        }
        if (loadedVersion < 3) {
            migrateV2ToV3();
        }
        // v4 adds an empty durable standing journal. Existing score history is imported explicitly,
        // never fabricated into live change envelopes.
        loadedVersion = FORMAT_VERSION;
        setDirty();
    }

    /**
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
    private void migrateV1ToV2() {
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
        McaReputation.LOGGER.info("[MCA: Reputation] upgrading saved data from format v{} to v2: "
                        + "{} receipt(s) recovered from retained incidents, {} record(s) marked terminal",
                loadedVersion, receipts, terminal);
    }

    /**
     * <h2>v2 to v3 — the structural half (§19.1)</h2>
     *
     * <p>Deterministic, independent of live world entities, and it moves no score. Every retained
     * public incident whose definition carries a {@code social_profile} gets an <em>unenriched
     * stub</em> and starts its profile clock at the age the scalar channel already observed. Nothing
     * else changes: baselines, contributions, ids, keys, witnesses, subjects, supersession links,
     * receipts, titles, high-water marks, metadata and decay immunity are all read and written back
     * exactly as they were.
     *
     * <p>A stub is not a zero and not a profile. Reading a missing field as "this deed had no social
     * meaning" would permanently mislabel every pre-upgrade rescue as unremarkable; reading it as a
     * configured profile would award quantities nobody earned. The marker says only "this is the kind
     * of deed that has meaning, and nothing has been established about it yet", which is exactly what
     * makes it a candidate for the budgeted §19.2 pass.
     *
     * <p>A <b>private</b> record is deliberately left with no payload at all. It can never contribute
     * public recognition or community facets (I03), so a stub would be a permanent upgrade candidate
     * that nothing may ever upgrade.
     *
     * <p>The enrichment half is not run here. It needs the datapack's profile content, which may not
     * have been published when the saved data is first read, so it is owed rather than done — see
     * {@link #advanceProfileMigration()}.
     */
    private void migrateV2ToV3() {
        int stubbed = 0;
        for (PlayerReputationRecord player : players.values()) {
            for (CommunityReputationRecord community : player.communities()) {
                for (IncidentRecord incident : community.incidents()) {
                    if (stubProfileEvidence(incident)) {
                        stubbed++;
                    }
                }
            }
        }
        profileMigration = ProfileMigrationState.opened(stubbed);
        McaReputation.LOGGER.info("[MCA: Reputation] upgrading saved data to format v3: {} incident(s) "
                        + "marked as unenriched legacy profile evidence, historical enrichment owed "
                        + "(manifest v{}). No score, receipt or title was changed.",
                stubbed, profileMigration.manifestVersion());
    }

    /**
     * Attaches the unenriched stub of §19.1 to one record, or returns false when it needs none.
     *
     * <p>Also used by {@link #advanceProfileMigration()}: if the incident definitions had not been
     * published when the file was read, the structural pass above found nothing to mark, and the
     * budgeted pass has to be able to finish the job rather than leave a save permanently unstubbed.
     */
    private static boolean stubProfileEvidence(IncidentRecord incident) {
        if (incident.hasProfileEvidence()) {
            return false;
        }
        if (incident.visibility().effective() == IncidentVisibility.PRIVATE) {
            return false;
        }
        Optional<ResourceLocation> profile = IncidentRegistry.get(incident.type())
                .flatMap(IncidentDefinition::socialProfile);
        if (profile.isEmpty()) {
            return false;
        }
        incident.adoptLegacyProfileClock();
        incident.attachProfileEvidence(IncidentProfileEvidence.legacyStub(profile.get()));
        return true;
    }

    /** What one record's enrichment attempt came to. */
    private enum EnrichmentOutcome {
        /** Frozen evidence was written from the manifest. */
        ENRICHED,
        /** The record is not a candidate: it already carries live, enriched or disabled evidence. */
        SKIPPED,
        /**
         * The manifest deliberately says nothing about this type, so it stays unenriched forever
         * (§19.2). Custom and generic completion incidents land here.
         */
        UNENRICHABLE,
        /** The content it needs is not published yet. Try again on the next pass.  */
        DEFERRED
    }

    /** Whether §19.2's budgeted enrichment pass still owes this save work. */
    public boolean isProfileMigrationPending() {
        return profileMigration.pending();
    }

    /** The migration cursor, for diagnostics and the coverage answer. */
    public ProfileMigrationState profileMigrationState() {
        return profileMigration;
    }

    /**
     * How complete this save's profile history is (§19.3). Never {@code COMPLETE} while a cursor
     * remains or a payload was quarantined.
     */
    public ProfileMigrationState.Coverage profileCoverage() {
        return profileMigration.coverage(SaveQuarantine.profilePayloadCount());
    }

    /** How many retained records are still waiting for, or permanently beyond, enrichment. */
    public int unenrichedIncidentCount() {
        int count = 0;
        for (PlayerReputationRecord player : players.values()) {
            for (CommunityReputationRecord community : player.communities()) {
                for (IncidentRecord incident : community.incidents()) {
                    if (incident.profileEvidence()
                            .filter(IncidentProfileEvidence::isEnrichmentCandidate).isPresent()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * One budgeted, resumable pass of §19.2's conservative historical enrichment.
     *
     * <p>Quiet by construction: it awards no standing, no title, no heart, no quest item and no
     * money. All it does is write frozen evidence for what the pre-upgrade save unambiguously
     * recorded, at 100% historical credit because the old system stored no repeat-credit decision and
     * §19.2 forbids inventing one.
     *
     * <p><b>Resumable and repeatable.</b> Players are visited in id order after the stored cursor, at
     * most {@link #ENRICHMENT_PLAYER_BUDGET} of them per call. Correctness does not depend on the
     * cursor surviving: enrichment only ever upgrades an <em>unenriched</em> stub, so a pass that ran
     * and was never persisted — {@code setDirty} is not a durable commit — simply runs again and
     * reaches the same state. That is what makes the interrupted case safe rather than merely
     * unlikely.
     *
     * <p>Returns early, having done nothing and kept the cursor, while the profile content is
     * unpublished. Enriching against an empty registry would mark records examined that nothing had
     * looked at.
     *
     * @return how many records this pass enriched
     */
    public int advanceProfileMigration() {
        return advanceProfileMigration(ENRICHMENT_PLAYER_BUDGET);
    }

    /** As {@link #advanceProfileMigration()}, with an explicit player budget. */
    public int advanceProfileMigration(int playerBudget) {
        if (!profileMigration.pending() || !writable() || playerBudget <= 0) {
            return 0;
        }
        ProfileRegistryBundle bundle = ProfileRegistryBundle.current();
        if (IncidentRegistry.size() == 0 || bundle.profiles().isEmpty()) {
            // The datapack has not published yet. Owed, not done.
            return 0;
        }
        // Sorted, so the cursor means the same thing on every pass: NBT key order is a hash order and
        // would let a resumed pass skip a player it never visited.
        List<UUID> ordered = new ArrayList<>(players.keySet());
        Collections.sort(ordered);
        Optional<UUID> cursor = profileMigration.cursor();

        int visited = 0;
        int stubbed = 0;
        int enriched = 0;
        int unenrichable = 0;
        UUID lastVisited = null;
        boolean exhausted = true;
        for (UUID playerId : ordered) {
            if (cursor.isPresent() && playerId.compareTo(cursor.get()) <= 0) {
                continue;
            }
            if (visited >= playerBudget) {
                exhausted = false;
                break;
            }
            visited++;
            lastVisited = playerId;
            PlayerReputationRecord player = players.get(playerId);
            if (player == null) {
                continue;
            }
            for (CommunityReputationRecord community : player.communities()) {
                for (IncidentRecord incident : community.incidents()) {
                    if (stubProfileEvidence(incident)) {
                        stubbed++;
                    }
                    switch (enrichProfileEvidence(incident, bundle)) {
                        case ENRICHED -> enriched++;
                        case UNENRICHABLE -> unenrichable++;
                        default -> {
                        }
                    }
                }
            }
        }
        // The cursor is null exactly when the pass reached the end of the player list, which is the
        // only condition under which coverage may stop reporting MIGRATING.
        profileMigration.advanced(exhausted ? null : lastVisited, stubbed, enriched, unenrichable);
        if (stubbed > 0 || enriched > 0 || !profileMigration.pending()) {
            setDirty();
        }
        if (enriched > 0 || stubbed > 0) {
            McaReputation.LOGGER.info("[MCA: Reputation] profile migration pass: {} record(s) enriched, "
                            + "{} newly stubbed, {} left unenrichable, coverage {}",
                    enriched, stubbed, unenrichable, profileCoverage().jsonName());
        }
        return enriched;
    }

    /**
     * Enriches one unenriched stub from the frozen manifest, or explains why it cannot.
     *
     * <p>Two narrowings do the real work here, both from §19.2. The <b>manifest</b> decides which
     * incident types may be spoken for at all, so a pack that repoints a built-in type at a generous
     * custom profile cannot retroactively award the difference. And only {@code recognition} and
     * {@code historical} channels are reconstructed: an {@code evaluative} channel is a judgement
     * about how a wrong reflects on the player now, and the old save recorded no authoritative
     * context for it. An old killing therefore contributes the recognition and the violence it
     * factually demonstrated, and nothing about culpability or remorse.
     */
    private static EnrichmentOutcome enrichProfileEvidence(IncidentRecord incident,
                                                           ProfileRegistryBundle bundle) {
        Optional<IncidentProfileEvidence> existing = incident.profileEvidence();
        if (existing.isEmpty() || !existing.get().isEnrichmentCandidate()) {
            return EnrichmentOutcome.SKIPPED;
        }
        Optional<ResourceLocation> manifestProfile =
                LegacyEnrichmentManifest.profileFor(incident.type());
        if (manifestProfile.isEmpty()) {
            return EnrichmentOutcome.UNENRICHABLE;
        }
        Optional<IncidentProfileDefinition> maybeDefinition = bundle.profile(manifestProfile.get());
        if (maybeDefinition.isEmpty()) {
            return EnrichmentOutcome.DEFERRED;
        }
        IncidentProfileDefinition definition = maybeDefinition.get();
        long step = definition.effectiveDecayStepTicks();
        long age = incident.profileElapsedTicks();

        Optional<IncidentProfileEvidence.Channel> recognition = definition.recognition()
                .filter(LegacyEnrichmentManifest::enrichable)
                .map(contribution -> channel(Optional.empty(), contribution, step, age));
        List<IncidentProfileEvidence.Channel> facets = new ArrayList<>();
        definition.facets().forEach((facet, contribution) -> {
            if (LegacyEnrichmentManifest.enrichable(contribution)) {
                facets.add(channel(Optional.of(facet), contribution, step, age));
            }
        });

        incident.attachProfileEvidence(IncidentProfileEvidence.of(
                IncidentProfileEvidence.Origin.LEGACY_ENRICHED, manifestProfile.get(),
                IncidentProfileEvidence.fingerprint(manifestProfile.get(), definition),
                bundle.generation(), recognition, facets,
                // §19.2: 100%, because the old system stored no decision to honour. The trackers
                // themselves start empty, so anti-grind accounting applies prospectively.
                CreditDecision.unlimited(CreditDecision.Reason.LEGACY_FULL_CREDIT)));
        return EnrichmentOutcome.ENRICHED;
    }

    /**
     * One reconstructed channel, aged to the record's own profile clock.
     *
     * <p>Credited equals authored (100% historical credit) and current is what the linear lifetime
     * leaves at the observed age, so an old deed arrives already faded — often to nothing — instead
     * of being handed a fresh lifetime by the upgrade.
     */
    private static IncidentProfileEvidence.Channel channel(Optional<ResourceLocation> facet,
                                                           IncidentProfileDefinition.Contribution contribution,
                                                           long step, long age) {
        long authored = contribution.authoredSubunits();
        long current = ProfileMath.remainingAt(authored, age, contribution.lifetimeTicks(), step);
        return new IncidentProfileEvidence.Channel(facet, authored, authored, current,
                contribution.lifetimeTicks(), step, contribution.resolutionMode(),
                contribution.resolutionBp());
    }

    /**
     * Fixture seam: opens the enrichment cursor exactly as the v2 to v3 upgrade does, so the golden
     * format-3 file can contain a half-finished migration without having to be built by loading an
     * older file through a registry the test does not control.
     */
    public void openProfileMigrationForTest(long stubbed, java.util.UUID cursor) {
        profileMigration = ProfileMigrationState.opened(stubbed);
        if (cursor != null) {
            profileMigration.advanced(cursor, 0L, 0L, 0L);
        }
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
