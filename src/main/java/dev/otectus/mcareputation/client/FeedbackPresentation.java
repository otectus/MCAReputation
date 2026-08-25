package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Chooses the feedback lines for one merged standing change (spec §28.3), as a pure function of the
 * packet and the client's presentation config.
 *
 * <p>Two rules the previous inline version got wrong, now stated once:
 *
 * <ul>
 *   <li>A fall that both crosses a tier and costs points shows <b>both</b> facts. The subdued
 *       tier line does not swallow the number — with {@code showExactScore} on, the player asked to
 *       see the delta, and hiding it exactly when the news is worst is the opposite of §28.3.</li>
 *   <li>An upward crossing that is not a personal best gets a quiet chat line of its own. The toast
 *       belongs to first-time milestones only; total silence left the climb unacknowledged.</li>
 * </ul>
 */
final class FeedbackPresentation {

    record Lines(Optional<Component> chat, Optional<Component> actionBar) {
    }

    private FeedbackPresentation() {
    }

    static Lines select(ReputationNetwork.ChangeS2C packet, boolean showNegativeTierMessages,
                        boolean showChangeActionBar, boolean showExactScore) {
        Optional<Component> chat = Optional.empty();
        if (packet.tierChanged() && packet.downward()) {
            if (showNegativeTierMessages) {
                // §28.3: a fall in standing gets a subdued message, never a celebration.
                chat = Optional.of(Component.translatable("mcareputation.feedback.tier_down",
                                packet.communityName(), packet.tierName())
                        .withStyle(ChatFormatting.GRAY));
            }
        } else if (packet.tierChanged() && !packet.firstTime()) {
            chat = Optional.of(Component.translatable("mcareputation.feedback.tier_up",
                            packet.communityName(), packet.tierName())
                    .withStyle(ChatFormatting.GRAY));
        }

        Optional<Component> actionBar = Optional.empty();
        if (showChangeActionBar && packet.delta() != 0) {
            // Polarity is carried by the sign and the wording, not by colour alone (§28.4).
            Component amount = Component.literal((packet.delta() > 0 ? "+" : "") + packet.delta());
            actionBar = Optional.of(showExactScore
                    ? Component.translatable("mcareputation.feedback.change", packet.communityName(),
                            amount, packet.tierName())
                    : Component.translatable("mcareputation.feedback.change_vague", packet.communityName(),
                            Component.translatable(packet.delta() > 0
                                    ? "mcareputation.feedback.improved"
                                    : "mcareputation.feedback.worsened"),
                            packet.tierName()));
        }
        return new Lines(chat, actionBar);
    }
}
