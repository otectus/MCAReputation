package dev.otectus.mcareputation.api;

import java.util.UUID;

/**
 * The villager a knowledge-filtered query is being asked on behalf of (§6 "Speaker-aware query").
 *
 * <p>{@link IncidentQuery#knownToSpeaker()} cannot be evaluated without one: awareness is a property
 * of a specific villager, not of an incident. A query carrying the flag with no speaker is refused
 * rather than silently broadened, which is why this type exists as a separate argument instead of a
 * nullable field on the selector.
 *
 * @param speakerId the villager doing the knowing
 * @param resident whether they currently live in the community being queried; a villager who moved
 *                 away keeps only what they witnessed themselves (§19.3)
 * @since MCA: Reputation 0.4.1
 */
public record SpeakerContext(UUID speakerId, boolean resident) {

    public static SpeakerContext of(UUID speakerId, boolean resident) {
        return new SpeakerContext(speakerId, resident);
    }
}
