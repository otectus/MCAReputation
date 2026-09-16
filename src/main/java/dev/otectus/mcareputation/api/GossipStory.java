package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentStatus;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * One incident as a story a villager could tell, including the corrections (§6 "Gossip story", DD12).
 *
 * <p>{@link ExternalGossipCandidate} answers "is there a line to say"; this answers the harder
 * question Conversations needs for amends dialogue: <em>has what the village believes about this deed
 * changed since the last time it was mentioned</em>. {@link #storyRevision} is the semantic answer.
 * It moves when the deed is resolved, disproven, or absorbed by another; it deliberately does
 * <b>not</b> move as ordinary decay shaves the contribution, so a speaker does not re-tell an
 * unchanged story every few days.
 *
 * <p>{@link #candidate} is present only when the fact is tellable on its own terms. A correction — a
 * disproven deed, or one a later incident absorbed — arrives with it empty: there is a change to
 * acknowledge, and no baseline line to say.
 *
 * @since MCA: Reputation 0.4.1
 */
public record GossipStory(UUID incidentId, ResourceLocation typeId, CommunityKey community,
                          IncidentStatus status, long storyRevision, int currentContribution,
                          int originalDelta, long occurredGameTime, boolean superseded,
                          Optional<UUID> supersededBy, boolean disproven,
                          Optional<ExternalGossipCandidate> candidate) {

    public GossipStory {
        supersededBy = supersededBy == null ? Optional.empty() : supersededBy;
        candidate = candidate == null ? Optional.empty() : candidate;
    }

    /**
     * True when this story exists to correct the record rather than to report a deed: the incident was
     * disproven, or a later one absorbed it. Conversations acknowledges these instead of repeating
     * them.
     */
    public boolean correction() {
        return superseded || disproven;
    }

    /** True when the story has moved on since the revision a teller last acted on. */
    public boolean newerThan(long lastToldRevision) {
        return storyRevision > lastToldRevision;
    }
}
