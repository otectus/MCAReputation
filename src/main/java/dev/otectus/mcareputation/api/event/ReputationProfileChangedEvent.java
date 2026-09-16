package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Posted after a player's <b>public profile</b> in one community has changed (§15).
 *
 * <p>This exists because recognition and facets move on their own authored lifetimes. A village can
 * change what it is able to say about a player on a day when the score, the tier and the standing
 * revision all stand still, and there is no honest way to announce that as a standing change:
 * {@code ReputationChangedEvent} would have to carry identical old and new scores, which every
 * consumer keyed on standing would either ignore or, worse, read as real movement. §15 forbids
 * repurposing it for exactly that reason, so the profile channel gets its own envelope and
 * {@code StandingChange} keeps its meaning.
 *
 * <h2>When it fires</h2>
 *
 * <ul>
 *   <li><b>An accepted deed that carried live profile evidence.</b> Posted after the canonical
 *       mutation, after the receipt, and after the standing envelope — the same order every other
 *       publication in this mod obeys, so a synchronous listener querying the store from here sees a
 *       complete transaction. {@link ChangeCause#DEED}, not quiet.</li>
 *   <li><b>A reconciliation pass that moved profile evidence and nothing else</b> — the
 *       profile-only change. {@link ChangeCause#DECAY} and quiet: ordinary fading must not toast, and
 *       a value oscillating across a label threshold must never announce "you became dependable"
 *       twice.</li>
 * </ul>
 *
 * <p>It is not a second notification of a standing change. A pass that moved both channels publishes
 * the standing envelope, which is already the invalidation signal a consumer needs; this event is
 * what makes the profile-only case observable at all.
 *
 * <h2>What a consumer should do with it</h2>
 *
 * <p>Treat it as an invalidation, not as a value. {@link #changedFacets} is bounded and is
 * <em>itemised only for a deed</em> — background fading reports an empty list, because computing the
 * exact set for every aged ledger would cost more than the answer is worth and §15 asks consumers to
 * re-evaluate at an interaction boundary anyway. Invalidate against {@link #profileRevision} and
 * re-query; do not cache the numbers here as a profile.
 *
 * <p>New dominant descriptors are derived presentation, never titles.
 *
 * @since MCA: Reputation 0.6.0
 */
public final class ReputationProfileChangedEvent extends ReputationEvent {

    /** One recognition channel plus the eight facet entries one deed may carry. */
    public static final int MAX_CHANGED_FACETS = 8;

    private final int oldRecognition;
    private final int newRecognition;
    private final List<ResourceLocation> changedFacets;
    private final long profileRevision;
    private final ChangeCause cause;
    @Nullable
    private final UUID incidentId;
    @Nullable
    private final ResourceLocation incidentType;
    @Nullable
    private final String operationKey;
    private final ResourceLocation source;
    private final boolean quiet;

    public ReputationProfileChangedEvent(UUID playerId, @Nullable ServerPlayer player,
                                         CommunityKey community, int oldRecognition,
                                         int newRecognition, List<ResourceLocation> changedFacets,
                                         long profileRevision, ChangeCause cause,
                                         @Nullable UUID incidentId,
                                         @Nullable ResourceLocation incidentType,
                                         @Nullable String operationKey, ResourceLocation source,
                                         boolean quiet) {
        super(playerId, player, community);
        this.oldRecognition = oldRecognition;
        this.newRecognition = newRecognition;
        this.changedFacets = bound(changedFacets);
        this.profileRevision = profileRevision;
        this.cause = cause == null ? ChangeCause.DEED : cause;
        this.incidentId = incidentId;
        this.incidentType = incidentType;
        this.operationKey = operationKey == null || operationKey.isBlank() ? null : operationKey;
        this.source = source;
        this.quiet = quiet;
    }

    /**
     * Bounded and explicitly sorted. Unbounded because a listener asked nicely is how an event
     * becomes a memory leak; sorted because a set whose order came from a hash map would make two
     * listeners disagree about the same change.
     */
    private static List<ResourceLocation> bound(@Nullable List<ResourceLocation> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> copy = new ArrayList<>();
        for (ResourceLocation facet : raw) {
            if (facet != null && !copy.contains(facet)) {
                copy.add(facet);
            }
            if (copy.size() >= MAX_CHANGED_FACETS) {
                break;
            }
        }
        copy.sort(Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(copy);
    }

    /** Public recognition before the change. */
    public int oldRecognition() {
        return oldRecognition;
    }

    /** Public recognition after it. */
    public int newRecognition() {
        return newRecognition;
    }

    public int recognitionDelta() {
        return newRecognition - oldRecognition;
    }

    /** The facets this change touched, sorted; empty for background fading. Never mutable. */
    public List<ResourceLocation> changedFacets() {
        return changedFacets;
    }

    /** The community record's profile revision after the change. Cache keys belong on this. */
    public long profileRevision() {
        return profileRevision;
    }

    /** Why the profile moved. */
    public ChangeCause cause() {
        return cause;
    }

    /** The deed behind the change, when a single deed caused it. */
    public Optional<UUID> incidentId() {
        return Optional.ofNullable(incidentId);
    }

    public Optional<ResourceLocation> incidentType() {
        return Optional.ofNullable(incidentType);
    }

    /** The producer's operation key, when the change arrived through a keyed delivery. */
    public Optional<String> operationKey() {
        return Optional.ofNullable(operationKey);
    }

    /** Who caused it — a core hook, a quest, a command, another mod, or this mod's own ageing. */
    public ResourceLocation source() {
        return source;
    }

    /**
     * Whether this is a background change: displays should follow it, deed feedback must not replay
     * for it.
     */
    public boolean quiet() {
        return quiet;
    }
}
