package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §28.3 presentation, across the config matrix. */
class FeedbackPresentationTest {

    private static final Component VILLAGE = Component.literal("Riverbend");
    private static final Component TIER = Component.translatable("mcareputation.tier.wary");

    private static ReputationNetwork.ChangeS2C packet(int delta, boolean tierChanged, boolean downward,
                                                      boolean firstTime) {
        return new ReputationNetwork.ChangeS2C(VILLAGE, delta, TIER, tierChanged, downward, firstTime);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    /** The regression: a fall that crosses a tier must not swallow the number the player asked for. */
    @Test
    void aDownwardTierCrossingShowsBothTheTierLineAndTheDelta() {
        FeedbackPresentation.Lines lines = FeedbackPresentation.select(
                packet(-30, true, true, false), true, true, true);
        assertEquals("mcareputation.feedback.tier_down", key(lines.chat().orElseThrow()));
        assertEquals("mcareputation.feedback.change", key(lines.actionBar().orElseThrow()));
    }

    @Test
    void suppressingNegativeTierMessagesStillShowsTheDelta() {
        FeedbackPresentation.Lines lines = FeedbackPresentation.select(
                packet(-30, true, true, false), false, true, true);
        assertTrue(lines.chat().isEmpty(), "the subdued line is opt-out");
        assertEquals("mcareputation.feedback.change", key(lines.actionBar().orElseThrow()),
                "but opting out of tier messages must not hide the score change");
    }

    @Test
    void aNonMilestoneClimbGetsAQuietTierLine() {
        FeedbackPresentation.Lines lines = FeedbackPresentation.select(
                packet(30, true, false, false), true, true, true);
        assertEquals("mcareputation.feedback.tier_up", key(lines.chat().orElseThrow()));
    }

    /** First-time milestones are the toast's job; announcing them twice would cheapen it. */
    @Test
    void aFirstTimeMilestoneLeavesTheChatToTheToast() {
        FeedbackPresentation.Lines lines = FeedbackPresentation.select(
                packet(30, true, false, true), true, true, true);
        assertTrue(lines.chat().isEmpty());
        assertEquals("mcareputation.feedback.change", key(lines.actionBar().orElseThrow()));
    }

    @Test
    void vagueModeHidesTheNumberButNotTheDirection() {
        FeedbackPresentation.Lines lines = FeedbackPresentation.select(
                packet(12, false, false, false), true, true, false);
        assertEquals("mcareputation.feedback.change_vague", key(lines.actionBar().orElseThrow()));
    }

    @Test
    void theActionBarRespectsItsToggleAndZeroDeltasSayNothing() {
        assertTrue(FeedbackPresentation.select(packet(12, false, false, false), true, false, true)
                .actionBar().isEmpty(), "showChangeActionBar off");
        assertTrue(FeedbackPresentation.select(packet(0, false, false, false), true, true, true)
                .actionBar().isEmpty(), "nothing moved, nothing to say");
    }
}
