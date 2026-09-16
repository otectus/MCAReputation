package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * What has to happen when this mod's config is loaded or reloaded (DD11).
 *
 * <p>Config events are <b>MOD-bus</b> events, which is why this class is registered from
 * {@code McaReputationMod}'s constructor rather than carrying an {@code @Mod.EventBusSubscriber} for
 * the Forge bus like the gameplay handlers do. It is also why nothing here touches the world directly:
 * a reload fires on the config-watcher thread, so every server-touching step is handed to the server
 * thread, and a reload with no server running has nothing to schedule.
 *
 * <h2>Why an event and not a poll</h2>
 *
 * <p>The alternative was to compare the config against itself from the server tick. Polling cannot
 * tell "this just changed" from "this was always so", and the whole point of the work below is that it
 * runs on the <em>transition</em>: a feature that has just been switched off has adornments left on
 * screen that nothing else will ever take down.
 */
public final class ReputationConfigLifecycle {

    /** The display switches as they were when the config was last read, for the off-transition. */
    private static volatile boolean scoreboardEnabled;
    private static volatile boolean tabListEnabled;

    /** The last policy read from the spec. Held so a reload has something to diff against. */
    private static volatile ReputationPolicy policy = ReputationPolicy.defaults();

    private ReputationConfigLifecycle() {
    }

    /** Registers both handlers on the given MOD bus. Called once, from the mod constructor. */
    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        modBus.addListener(ReputationConfigLifecycle::onLoading);
        modBus.addListener(ReputationConfigLifecycle::onReloading);
    }

    /** The last policy snapshot taken from the spec; for diagnostics and for tests. */
    public static ReputationPolicy policy() {
        return policy;
    }

    public static void onLoading(ModConfigEvent.Loading event) {
        if (!isOurs(event.getConfig())) {
            return;
        }
        // First read: there is no previous state to transition from, so nothing is taken down.
        latch();
    }

    public static void onReloading(ModConfigEvent.Reloading event) {
        if (!isOurs(event.getConfig())) {
            return;
        }
        boolean scoreboardWas = scoreboardEnabled;
        boolean tabListWas = tabListEnabled;
        latch();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return; // no world; there is nothing on screen to clean up
        }
        server.execute(() -> {
            try {
                StandingDisplay.applyConfigChange(server, scoreboardWas, tabListWas);
            } catch (Throwable t) {
                McaReputation.LOGGER.warn("[MCA: Reputation] could not apply the reloaded display "
                        + "config; the previous display may be stale until something refreshes it", t);
            }
        });
    }

    /** Rereads everything cached from the config, through the guarded accessors only. */
    private static void latch() {
        policy = McaReputationConfig.snapshot();
        scoreboardEnabled = McaReputationConfig.scoreboardObjectiveEnabled();
        tabListEnabled = McaReputationConfig.tabListTierEnabled();
    }

    /** Another mod's config reload is not ours to react to. */
    private static boolean isOurs(ModConfig config) {
        return config != null && McaReputation.MOD_ID.equals(config.getModId());
    }
}
