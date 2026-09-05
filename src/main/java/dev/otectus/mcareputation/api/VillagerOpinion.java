package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.UUID;

/**
 * How one villager, personally, feels about one player (§19.3 read through §13.4).
 *
 * <p>Derived, never stored. The village ledger is still the only record; this is that ledger read
 * through what this particular villager saw, was part of, or has had time to hear about. Nothing here
 * adds a second score to the save, and nothing a player does to a villager directly — gifts, trades,
 * clicks — moves it, because those are farmable and a village's memory is not for sale.
 *
 * @param villagerId     the villager this opinion belongs to
 * @param villagerName   their name as the server knows it, possibly empty
 * @param community      the community whose ledger the opinion was folded from
 * @param opinion        the weighted standing, clamped to the same window as a score
 * @param tierId         the tier {@code opinion} falls in on the default ladder, so bands retune with it
 * @param basis          the strongest way this villager came to know anything about the player
 * @param knownIncidents how many of the player's deeds here this villager knows about at all
 * @since MCA: Reputation 0.4.0
 */
public record VillagerOpinion(UUID villagerId, String villagerName, CommunityKey community,
                              int opinion, String tierId, OpinionBasis basis, int knownIncidents) {

    /**
     * Why a villager holds the view they do, strongest first.
     *
     * <p>{@link #INVOLVED} beats {@link #WITNESSED}: being the villager a deed was done <em>to</em> is
     * a stronger claim on an opinion than having watched it happen to someone else.
     */
    public enum OpinionBasis {

        /** The villager was a subject of at least one deed: it happened to them. */
        INVOLVED("involved", "mcareputation.opinion.basis.involved"),

        /** The villager saw at least one deed themselves. */
        WITNESSED("witnessed", "mcareputation.opinion.basis.witnessed"),

        /** Everything this villager knows, they were told. */
        HEARSAY("hearsay", "mcareputation.opinion.basis.hearsay"),

        /** The villager knows nothing about this player at all. */
        NONE("none", "mcareputation.opinion.basis.none");

        private final String jsonName;
        private final String translationKey;

        OpinionBasis(String jsonName, String translationKey) {
            this.jsonName = jsonName;
            this.translationKey = translationKey;
        }

        /** The stable string form used in JSON and on the API boundary. */
        public String jsonName() {
            return jsonName;
        }

        /**
         * The lang key describing this basis to a player. Held as a literal per constant rather than
         * assembled from {@link #jsonName()} so the reference is findable in source — a concatenated
         * key is exactly the kind that quietly rots.
         */
        public String translationKey() {
            return translationKey;
        }
    }
}
