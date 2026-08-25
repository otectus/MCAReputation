package dev.otectus.mcareputation.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.compat.McaScreenCompat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Client entry points (spec §28.1): the Standing button on MCA's interaction screen, and the keybind.
 *
 * <p>Both are optional conveniences layered on a server-authoritative screen. Neither can produce
 * information the server did not send, and neither is required — a player with the button switched off
 * and the keybind unbound can still be shown the screen by another mod's link, or by an
 * {@code OpenScreenS2C} push from a validated server-side interaction (§29.7).
 *
 * <p>The keybind ships <b>unbound</b> on purpose. A default key in a modpack with dozens of mods is a
 * conflict waiting to happen, and this screen is reachable without it.
 */
@Mod.EventBusSubscriber(modid = McaReputation.MOD_ID, value = Dist.CLIENT)
public final class ReputationClient {

    /**
     * The entity the local player most recently right-clicked.
     *
     * <p>This is how the Standing button knows which villager — and therefore which village — the
     * player is looking at, without reading MCA's private screen field. It is a hint the client sends
     * and the server validates (existence, dimension, MCA villager, ≤ 12 blocks) before honouring, so
     * a wrong or stale value costs at most an unhelpful button press (§27.2).
     */
    private static int lastInteractedEntityId;
    private static long lastInteractedAtTick;

    /** After this long the hint is considered stale and is not sent. */
    private static final long HINT_LIFETIME_TICKS = 200L;

    private static long clientTick;

    private ReputationClient() {
    }

    // ------------------------------------------------------------------
    // Keybind
    // ------------------------------------------------------------------

    public static final KeyMapping OPEN_REPUTATION = new KeyMapping(
            "key.mcareputation.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), // unbound by default (§28.1)
            "key.categories.mcareputation");

    @Mod.EventBusSubscriber(modid = McaReputation.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_REPUTATION);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTick++;
        ClientReputationData.flushChanges();
        while (OPEN_REPUTATION.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.screen != null) {
                continue;
            }
            // Opens the known-community index, letting the server preselect wherever the player is.
            ClientReputationData.request(0, Optional.empty());
            minecraft.setScreen(new ReputationScreen(null));
        }
    }

    // ------------------------------------------------------------------
    // Interaction hint
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getEntity().level().isClientSide()) {
            return;
        }
        Entity target = event.getTarget();
        if (target != null) {
            lastInteractedEntityId = target.getId();
            lastInteractedAtTick = clientTick;
        }
    }

    private static int contextEntityId() {
        return clientTick - lastInteractedAtTick <= HINT_LIFETIME_TICKS ? lastInteractedEntityId : 0;
    }

    // ------------------------------------------------------------------
    // Standing button on MCA's interaction screen
    // ------------------------------------------------------------------

    /**
     * Adds the Standing button to MCA's villager interaction screen.
     *
     * <p>Uses Forge's screen-init event rather than a mixin, so there is no MCA-internal signature to
     * drift against (§11). MCA's {@code InteractScreen} chains {@code super.render} and
     * {@code super.mouseClicked}, so a vanilla widget added this way renders and responds normally.
     *
     * <p>The button is placed in the screen's own top-left margin and its width is clamped to the
     * available space, so it stays reachable at small GUI scales instead of running off the edge.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!McaReputationConfig.showReputationButton()) {
            return;
        }
        Screen screen = event.getScreen();
        if (!McaScreenCompat.isVillagerInteractScreen(screen)) {
            return;
        }
        int buttonWidth = Math.min(70, Math.max(48, screen.width / 6));
        Button standing = Button.builder(Component.translatable("mcareputation.button.standing"),
                        button -> openFromInteraction(screen))
                .bounds(4, 4, buttonWidth, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("mcareputation.button.standing.tooltip")))
                .build();
        event.addListener(standing);
    }

    private static void openFromInteraction(Screen parent) {
        ClientReputationData.request(contextEntityId(), Optional.empty());
        Minecraft.getInstance().setScreen(new ReputationScreen(parent));
    }

    // ------------------------------------------------------------------
    // Session hygiene
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLoggedOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // A cached snapshot from one world must never be shown in another.
        ClientReputationData.clear();
        lastInteractedEntityId = 0;
    }
}
