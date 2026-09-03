package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.Optional;

/**
 * Which community the standing screen opens on when the player asked about no village in particular
 * (spec §27.2, §28.2).
 *
 * <p>A class of its own, and not a private method inside {@link ReputationNetwork}, for two reasons.
 * It is the one decision in the reply that is pure policy rather than plumbing, and it needs to be
 * callable from a unit test and from {@code /mcareputation debug standing} — neither of which can
 * touch {@code ReputationNetwork}, whose payload registration needs a running game.
 *
 * <h2>The bug this class is named after</h2>
 *
 * <p>Order matters here and used not to. "Wherever the player is standing" won outright, and
 * {@link ReputationNetwork#buildSnapshot} synthesises an empty, floor-tier detail for a community the
 * player has no record for. So a player standing anywhere near a village they had never dealt with was
 * shown <em>Stranger — 25 more to Acquaintance</em>, however much standing they had earned elsewhere;
 * and with a single real community the selector drew no arrows to cycle away with, so there was no way
 * to reach it. The write key (the home village of the villager a deed was about) and the read key (the
 * nearest village to the player's feet) answer two different questions, and this is where the two
 * diverged.
 */
public final class SnapshotSelection {

    private SnapshotSelection() {
    }

    /**
     * The unprompted selection: where you are, but only if you have a history there; otherwise the
     * standing you actually have; and only when you have none anywhere, "you are a stranger here" —
     * which is then the honest answer rather than a mask over a real record.
     *
     * @param here      the community the player is standing in, if any
     * @param knowsHere whether the player already has a record for {@code here}
     * @param bestKnown the player's best-standing community, if they have any record at all
     */
    public static Optional<CommunityKey> unprompted(Optional<CommunityKey> here, boolean knowsHere,
                                                    Optional<CommunityKey> bestKnown) {
        if (here.isPresent() && knowsHere) {
            return here;
        }
        return bestKnown.isPresent() ? bestKnown : here;
    }
}
