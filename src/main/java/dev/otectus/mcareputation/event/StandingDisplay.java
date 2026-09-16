package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Standing shown outside the standing screen: a scoreboard objective and a tab-list suffix (§28.4).
 *
 * <h2>Which village it shows</h2>
 *
 * <p>The same one the screen would open on, through
 * {@link ReputationService#unpromptedCommunity}. Two surfaces answering "how do they see me here"
 * with two different villages is precisely the drift DIAGNOSIS.md §2 hop 7b describes, so neither
 * surface gets its own opinion — this class only caches the answer.
 *
 * <h2>Why there is a cache at all</h2>
 *
 * <p>{@code TabListNameFormat} fires while the player list is being built, for every player, and
 * resolving a village involves a world lookup. So the display value is computed on the events that can
 * change it — login, dimension change, a standing or tier change — plus a periodic sweep for the one
 * thing that raises no event, walking from one village into another; and the tab-list hook only reads
 * what is already cached.
 *
 * <p>Both displays are off by default, and when both are off nothing here resolves anything: the
 * early return in {@link #anyDisplayEnabled()} happens before any village lookup.
 *
 * @since MCA: Reputation 0.4.0
 */
@EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class StandingDisplay {

    /** What is currently on display for one player. */
    record Shown(CommunityKey key, int score, String tierId) {
    }

    private static final Map<UUID, Shown> SHOWN = new LinkedHashMap<>();

    /** Objective names we have already refused to touch, so the refusal is logged once each (DD13). */
    private static final Set<String> REFUSED = new HashSet<>();

    private static int tickCounter;

    private StandingDisplay() {
    }

    // ------------------------------------------------------------------
    // Pure decisions
    // ------------------------------------------------------------------

    /**
     * Whether anything visible changed between two computed values. Either side may be {@code null},
     * meaning "the player has no village to show" — appearing and disappearing are both changes.
     */
    static boolean shouldRefresh(@Nullable Shown old, @Nullable Shown next) {
        return !Objects.equals(old, next);
    }

    /**
     * Whether the tab-list name has to be rebuilt. Narrower than {@link #shouldRefresh}: only the tier
     * name appears there, so a score that moved within one tier is not worth resending a player-info
     * packet for.
     */
    static boolean tierChanged(@Nullable Shown old, @Nullable Shown next) {
        String oldTier = old == null ? null : old.tierId();
        String nextTier = next == null ? null : next.tierId();
        return !Objects.equals(oldTier, nextTier);
    }

    /** The display name our objective wears, and the mark that proves the objective is ours. */
    static Component objectiveMarker() {
        return Component.translatable("mcareputation.scoreboard.objective");
    }

    /** The tab-list entry: whatever name was already built, plus the tier. */
    static Component withSuffix(Component base, Component tierName) {
        return base.copy().append(Component.translatable("mcareputation.tablist.suffix", tierName));
    }

    // ------------------------------------------------------------------
    // Triggers
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refresh(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refresh(player);
        }
    }

    /** Only for an online player: {@code player()} is empty when the change landed on someone away. */
    @SubscribeEvent
    public static void onChanged(ReputationChangedEvent event) {
        event.player().ifPresent(StandingDisplay::refresh);
    }

    @SubscribeEvent
    public static void onTierChanged(ReputationTierChangedEvent event) {
        event.player().ifPresent(StandingDisplay::refresh);
    }

    /**
     * The sweep. Nothing announces that a player walked out of one village and into the next, so the
     * only way for the display to follow them is to ask again periodically.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!anyDisplayEnabled()) {
            return;
        }
        if (++tickCounter < McaReputationConfig.displayRefreshIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    /** Drops the cache entry and the score row for a departing player. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SHOWN.remove(player.getUUID());
        if (McaReputationConfig.scoreboardObjectiveEnabled()) {
            writeScore(player, null);
        }
    }

    /**
     * Appends the tier to the tab-list name at {@link EventPriority#LOW}, so mods that replace the
     * name outright have already had their say and this adds to their result rather than losing to it.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (!McaReputationConfig.tabListTierEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Shown shown = SHOWN.get(player.getUUID());
        if (shown == null) {
            return;
        }
        Optional<ReputationTier> tier = ReputationTiers.getDefault().byId(shown.tierId());
        if (tier.isEmpty()) {
            return; // a datapack removed the tier this player was last shown at; leave the name alone
        }
        Component base = event.getDisplayName() != null ? event.getDisplayName() : player.getName();
        event.setDisplayName(withSuffix(base, tier.get().name()));
    }

    /** Clears every cached display on server stop; see {@code McaReputationMod.onServerStopped}. */
    public static void clearAll() {
        SHOWN.clear();
        REFUSED.clear();
        tickCounter = 0;
    }

    // ------------------------------------------------------------------
    // Config lifecycle (DD11)
    // ------------------------------------------------------------------

    /**
     * Drops every cached display decision. Called when the config is reloaded: the objective name, the
     * refresh interval and both switches may all have moved, and a cached decision made under the old
     * values would keep a stale row alive until something else happened to the player.
     */
    public static void invalidateCache() {
        SHOWN.clear();
        REFUSED.clear();
        tickCounter = 0;
    }

    /**
     * Takes down the adornments of a feature that has just been switched off, and rebuilds the ones
     * still on. Off then on must leave no stale suffix and no stale score (§5 F16 row 5).
     *
     * @param scoreboardWasEnabled whether the scoreboard row was on before this config change
     * @param tabListWasEnabled    whether the tab-list suffix was on before this config change
     */
    public static void applyConfigChange(MinecraftServer server, boolean scoreboardWasEnabled,
                                         boolean tabListWasEnabled) {
        if (server == null) {
            return;
        }
        boolean scoreboardNow = McaReputationConfig.scoreboardObjectiveEnabled();
        boolean tabListNow = McaReputationConfig.tabListTierEnabled();
        if (scoreboardWasEnabled && !scoreboardNow) {
            removeOwnedObjective(server);
        }
        invalidateCache();
        if (tabListWasEnabled && !tabListNow) {
            // The suffix lives in the name itself, so the only way to take it off is to rebuild the
            // name -- which the hook now declines to decorate.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.refreshTabListName();
            }
        }
        if (anyDisplayEnabled()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                refresh(player);
            }
        }
    }

    /**
     * Removes the configured objective, and with it every score we wrote, but only when it is provably
     * ours. A foreign objective wearing the configured name is left exactly as it is (DD13).
     */
    static void removeOwnedObjective(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        String name = McaReputationConfig.scoreboardObjectiveName();
        Objective objective = scoreboard.getObjective(name);
        if (decide(objective, false) != ScoreboardOwnership.Decision.REMOVE) {
            return;
        }
        // Our rows go with it: removeObjective drops every score held against it.
        scoreboard.removeObjective(objective);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static boolean anyDisplayEnabled() {
        return McaReputationConfig.scoreboardObjectiveEnabled() || McaReputationConfig.tabListTierEnabled();
    }

    private static void refresh(ServerPlayer player) {
        if (!anyDisplayEnabled()) {
            return;
        }
        try {
            Shown next = compute(player);
            Shown old = next == null ? SHOWN.remove(player.getUUID()) : SHOWN.put(player.getUUID(), next);
            if (!shouldRefresh(old, next)) {
                return;
            }
            if (McaReputationConfig.scoreboardObjectiveEnabled()) {
                writeScore(player, next);
            }
            if (McaReputationConfig.tabListTierEnabled() && tierChanged(old, next)) {
                player.refreshTabListName();
            }
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] could not refresh the standing display for {}",
                    player.getGameProfile().getName(), t);
        }
    }

    /** The village the screen would open on and the player's standing there, or null for neither. */
    @Nullable
    private static Shown compute(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        Optional<CommunityKey> here = CommunityResolver.resolveNearest(level, player.blockPosition());
        Optional<CommunityKey> selected =
                ReputationService.unpromptedCommunity(player.server, player.getUUID(), here);
        if (selected.isEmpty()) {
            return null;
        }
        int score = ReputationService.scoreOrZero(player.server, player.getUUID(), selected.get());
        return new Shown(selected.get(), score, ReputationService.currentTierId(score));
    }

    /**
     * The ownership question for the objective with the configured name, asked of the live scoreboard
     * and answered by {@link ScoreboardOwnership}.
     */
    private static ScoreboardOwnership.Decision decide(@Nullable Objective objective,
                                                       boolean featureEnabled) {
        return ScoreboardOwnership.decide(objective != null,
                objective != null && objective.getCriteria() == ObjectiveCriteria.DUMMY,
                objective != null && objectiveMarker().equals(objective.getDisplayName()),
                featureEnabled);
    }

    /**
     * Writes, or clears, one player's row. The objective is created on demand, and an existing one is
     * adopted only when it is provably ours — dummy criteria and our own display-name marker (DD13).
     * Anything else keeps the name and is never written to, never removed, and reported once.
     *
     * <p>Nothing here ever calls {@code setDisplayObjective}: where standing is shown is the server's
     * decision, not this mod's.
     */
    private static void writeScore(ServerPlayer player, @Nullable Shown shown) {
        Scoreboard scoreboard = player.server.getScoreboard();
        String name = McaReputationConfig.scoreboardObjectiveName();
        Objective objective = scoreboard.getObjective(name);
        ScoreboardOwnership.Decision decision = decide(objective, true);
        if (decision == ScoreboardOwnership.Decision.REFUSE) {
            if (REFUSED.add(name)) {
                McaReputation.LOGGER.warn("[MCA: Reputation] the scoreboard objective '{}' already "
                        + "exists and is not ours; leaving it alone and showing no standing on it. "
                        + "Point scoreboardObjectiveName at a free name, or remove that objective.",
                        name);
            }
            return;
        }
        if (!ScoreboardOwnership.mayWrite(decision)) {
            return;
        }
        if (decision == ScoreboardOwnership.Decision.CREATE) {
            // 1.21 added the auto-update flag and the number format; neither is this mod's business,
            // so the row is a plain integer that only this class ever writes.
            objective = scoreboard.addObjective(name, ObjectiveCriteria.DUMMY, objectiveMarker(),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
        }
        if (shown == null) {
            scoreboard.resetSinglePlayerScore(player, objective);
        } else {
            scoreboard.getOrCreatePlayerScore(player, objective).set(shown.score());
        }
    }
}
