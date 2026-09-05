package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.data.TierReachedTrigger;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Feeds tier changes to the {@code mcareputation:tier_reached} advancement trigger (spec §30.2).
 *
 * <p>Advancements only exist for a player who is actually here, so an offline change is dropped
 * rather than queued: the standing itself is saved, and the trigger fires the next time the player
 * crosses a boundary in person. That matches how the toast in {@code ReputationFeedback} behaves.
 *
 * @since MCA: Reputation 0.4.0
 */
@Mod.EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class ReputationAdvancementEvents {

    private ReputationAdvancementEvents() {
    }

    @SubscribeEvent
    public static void onTierChanged(ReputationTierChangedEvent event) {
        // player() is the event's own getPlayerList().getPlayer(id) lookup: present only when online.
        event.player().ifPresent(player -> TierReachedTrigger.INSTANCE.trigger(
                player, event.newTierId(), event.community(), event.upward()));
    }
}
