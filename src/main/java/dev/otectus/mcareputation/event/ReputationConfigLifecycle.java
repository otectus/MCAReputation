package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.reputation.ReconciliationService;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * What has to happen when this mod's config is loaded or reloaded (DD11).
 *
 * <p>Config events are <b>MOD-bus</b> events, which is why this class is registered from
 * {@code McaReputationMod}'s constructor rather than carrying an {@code @EventBusSubscriber} for the
 * game bus like the gameplay handlers do. It is also why nothing here touches the world directly: a
 * reload fires on the config-watcher thread, so every server-touching step is handed to the server
 * thread, and a reload with no server running has nothing to schedule.
 *
 * <h2>Why an event and not a poll</h2>
 *
 * <p>The alternative was to compare the config against itself from the server tick. Polling cannot
 * tell "this just changed" from "this was always so", and the whole point of the work below is that it
 * runs on the <em>transition</em>: a feature that has just been switched off has adornments left on
 * screen that nothing else will ever take down.
 *
 * <p>Since 0.6.0 the same argument carries {@code enableProfiles}. {@link #latch()} rereads the whole
 * policy — which now includes §20's profile switches — and the reload, not the next query, is what
 * records that a disabled interval was disabled. That ordering matters: {@code latch()} runs first so
 * the transition is reported against the policy that is now in force, and a record nobody reads during
 * the disabled interval still loses only the ticks the freeze log says were active (§12.2).
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
    public static void register(IEventBus modBus) {
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
                // §12.2: the profile-aging transition has to be captured when it happens. A record
                // nobody reads during a disabled interval cannot tell afterwards whether the interval
                // counted, so the reload — not the next query — is what records that it did not.
                ReconciliationService.observeProfilePolicy(policy, ReputationSavedData.get(server),
                        server.overworld().getGameTime());
            } catch (Throwable t) {
                McaReputation.LOGGER.warn("[MCA: Reputation] could not record the reloaded profile "
                        + "policy transition; profile aging falls back to what the next read observes",
                        t);
            }
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
