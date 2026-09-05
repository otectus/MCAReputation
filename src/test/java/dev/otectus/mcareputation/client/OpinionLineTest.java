package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.api.VillagerOpinion;
import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one line the standing screen adds for the villager the player is looking at. */
class OpinionLineTest {

    private static ReputationNetwork.OpinionSummary summary(VillagerOpinion.OpinionBasis basis) {
        return new ReputationNetwork.OpinionSummary(Component.literal("Anna"),
                Component.translatable("mcareputation.tier.friend"), basis);
    }

    @Test
    void theLineIsHiddenWhenTheOptionIsOff() {
        assertTrue(ReputationScreen.opinionLine(
                Optional.of(summary(VillagerOpinion.OpinionBasis.WITNESSED)), false).isEmpty());
    }

    /** The keybind opens the screen with no villager in the question, so there is nothing to say. */
    @Test
    void theLineIsHiddenWhenTheServerSentNoOpinion() {
        assertTrue(ReputationScreen.opinionLine(Optional.empty(), true).isEmpty());
    }

    @Test
    void aVillagerWithAViewIsNamedAlongsideTheirTierAndBasis() {
        Component line = ReputationScreen.opinionLine(
                Optional.of(summary(VillagerOpinion.OpinionBasis.HEARSAY)), true).orElseThrow();
        assertEquals("mcareputation.screen.opinion",
                ((net.minecraft.network.chat.contents.TranslatableContents) line.getContents()).getKey());
    }

    /** "Nobody here knows you" is an answer, and it gets its own shorter line. */
    @Test
    void aVillagerWhoKnowsNothingGetsTheNoneLine() {
        Component line = ReputationScreen.opinionLine(
                Optional.of(summary(VillagerOpinion.OpinionBasis.NONE)), true).orElseThrow();
        assertEquals("mcareputation.screen.opinion.none",
                ((net.minecraft.network.chat.contents.TranslatableContents) line.getContents()).getKey());
    }
}
