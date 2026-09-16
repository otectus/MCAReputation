package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.McaReputation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only MOD-bus handler that registers the {@code OPEN_REPUTATION} keybind.
 *
 * <p>This class handles the MOD-bus half of client registration for {@link ReputationClient}, while
 * gameplay handlers subscribe to the FORGE bus there. By keeping each class to one event bus, the
 * boundary between registration and gameplay becomes explicit.
 */
@Mod.EventBusSubscriber(modid = McaReputation.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ReputationClientRegistration {

    private ReputationClientRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ReputationClient.OPEN_REPUTATION);
    }
}
