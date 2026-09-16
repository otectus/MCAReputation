package dev.otectus.mcareputation.api.profile;

/**
 * How widely known a player is in one community, and nothing else (§7.1, §14.2).
 *
 * <p>Recognition is <b>not</b> friendship, standing, or a percentage of residents who have heard a
 * rumour: it is the aggregate significance of the public evidence this community holds. A revered
 * hero and an infamous murderer can carry the same recognition. §13.3 is explicit that recognition
 * may authorise "I have heard your name" and may never authorise familiarity.
 *
 * @param value         the public recognition integer, {@code 0..1000}
 * @param tierId        the recognition ladder tier {@code value} falls in
 * @param evidenceCount how many retained deeds still contribute recognition
 * @since MCA: Reputation 0.6.0
 */
public record RecognitionValue(int value, String tierId, int evidenceCount) {

    public RecognitionValue {
        tierId = tierId == null ? "" : tierId;
        value = Math.max(0, value);
        evidenceCount = Math.max(0, evidenceCount);
    }

    /** The "nothing is known here" answer, on the ladder's own floor tier. */
    public static RecognitionValue none(String floorTierId) {
        return new RecognitionValue(0, floorTierId, 0);
    }

    /**
     * Whether any live evidence backs this value.
     *
     * <p>False is a real answer: a complete-history community that has simply never seen this player
     * legitimately reads zero and unobserved (§14.5).
     */
    public boolean observed() {
        return evidenceCount > 0;
    }
}
