package dev.otectus.mcareputation.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * One fact a villager knows and could bring up, normalized for MCA: Conversations (spec §30.4).
 *
 * <p>The division of labour here is the whole point of §4. Reputation knows <em>that</em> something
 * happened, <em>who</em> knows about it, and <em>when</em>; Conversations knows how a nervous
 * blacksmith phrases bad news at dusk. So this record carries a phrase key and arguments, never a
 * finished sentence, and Conversations renders it through its own personality overlays and locales.
 *
 * <p>Conversations merges these with its own built-in gossip log and picks the newest deterministically
 * (creation time, then stable id). Nothing here is stored on the Conversations side: if Reputation is
 * removed, external candidates simply stop appearing and built-in gossip is untouched (§30.4).
 *
 * <p>{@code arguments} is capped at four, matching the normalized candidate's limit in §30.4 — enough
 * for two subjects plus a village and a quantity, without letting a datapack build an unbounded
 * format string.
 */
public record ExternalGossipCandidate(
        UUID incidentId,
        ResourceLocation typeId,
        long createdGameTime,
        long ageTicks,
        String communityKey,
        String communityName,
        String tone,
        String phraseKey,
        List<Component> arguments,
        int contribution) {

    /** §30.4: external translation arguments may exceed the old fixed A/B pair, but stop at four. */
    public static final int MAX_ARGUMENTS = 4;

    public ExternalGossipCandidate {
        arguments = arguments == null ? List.of() : List.copyOf(arguments.subList(0, Math.min(arguments.size(), MAX_ARGUMENTS)));
        tone = tone == null ? "" : tone;
        phraseKey = phraseKey == null ? "" : phraseKey;
        communityKey = communityKey == null ? "" : communityKey;
        communityName = communityName == null ? "" : communityName;
    }

    /**
     * The identity Conversations uses for its per-teller/per-listener "already told" memory, which it
     * continues to own in MCA's {@code LongTermMemory} under
     * {@code mcaconversations.gossip.<eventUuid>.<playerUuid>} (§19.4, §30.4). Reputation answers only
     * whether the teller knows the fact; it never tracks who has been told.
     */
    public UUID alreadyToldIdentity() {
        return incidentId;
    }

    /** Positive deeds are praise, negative ones condemnation; used when no explicit tone is authored. */
    public String effectiveTone() {
        if (!tone.isEmpty()) {
            return tone;
        }
        if (contribution > 0) {
            return "praise";
        }
        return contribution < 0 ? "condemnation" : "neutral";
    }
}
