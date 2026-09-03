package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaCompat;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.WitnessResolver;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The core automatic detection layer (spec §20): the narrow, reliable set of MCA actions that create
 * standing without any datapack authoring.
 *
 * <p>§20.3 lists what is deliberately <em>not</em> here — trades, gifts, entering a village, curing,
 * generic mob kills, sleeping, marriage, block placement, chat. Every one of them is either farmable,
 * already owned by another system, or impossible to attribute reliably. Detecting fewer things well
 * is the design.
 *
 * <p>Everything in this class is server side. The damage and death events fire on both sides; each
 * handler bails immediately on a client level, so no client ever computes standing.
 */
@EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class ReputationGameplayEvents {

    private static int tickCounter;

    private ReputationGameplayEvents() {
    }

    // ------------------------------------------------------------------
    // Assault
    // ------------------------------------------------------------------

    /**
     * Records harm to a villager, and separately notes when a villager harms a <em>player</em> so the
     * self-defence window has something to consult.
     *
     * <p>Uses {@link LivingDamageEvent.Post} — the 1.21.1 successor to Forge's {@code LivingHurtEvent}
     * — because {@link LivingDamageEvent.Post#getNewDamage()} is the health actually lost after armour,
     * enchantments, absorption and every other reduction. {@code minimumIncidentDamage} is meant to
     * ignore chip damage, and a pre-mitigation number would not do that, which rules out
     * {@code LivingIncomingDamageEvent} for this purpose.
     *
     * <p>{@code receiveCanceled = false} is gone with the event: {@code Post} fires after the damage
     * has been applied and is not the cancellable stage, so a hit another mod cancelled never reaches
     * here at all. {@link EventPriority#LOWEST} is kept only for deterministic ordering against other
     * post-damage listeners; it is no longer what guarantees cancellation handling.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !(target.level() instanceof ServerLevel level)) {
            return;
        }
        long gameTime = level.getServer().overworld().getGameTime();

        // A villager hitting a player: not an incident, but the fact the self-defence rule needs.
        if (target instanceof ServerPlayer player) {
            Entity attacker = event.getSource().getEntity();
            // Only a hit that actually cost health opens the self-defence window; a fully absorbed
            // or negated swing is not something the player needs to answer for retaliating against.
            if (attacker != null && event.getNewDamage() > 0.0F && McaCompat.isMcaVillager(attacker)) {
                AssaultTracker.recordVillagerHitPlayer(attacker.getUUID(), player.getUUID(), gameTime);
            }
            return;
        }

        if (!McaReputationConfig.enabled() || !McaReputationConfig.coreAssaultEnabled()) {
            return;
        }
        // Another mod may own this deed (integration spec 7.1). MCA: Crime detects the same swing and
        // writes its own legal case, so producing an incident here too would charge the player twice.
        // Checked per event rather than once at startup: an authority that disables itself mid-session
        // hands detection straight back, and the check sits ahead of every coalescing/folding path
        // below so none of that state is touched while somebody else owns the deed.
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_ASSAULT)) {
            return;
        }
        if (!McaCompat.isLivingMcaVillager(target)) {
            return;
        }
        if (event.getNewDamage() < McaReputationConfig.minimumIncidentDamage()) {
            return;
        }
        Optional<ServerPlayer> responsible = attribute(event.getSource());
        if (responsible.isEmpty()) {
            return;
        }
        ServerPlayer player = responsible.get();
        Optional<CommunityKey> community = CommunityResolver.resolve(target);
        if (community.isEmpty()) {
            // A villager with no village and none nearby has no public to be outraged (§12.2).
            return;
        }
        recordAssault(level, player, target, community.get(), event.getNewDamage(), gameTime);
    }

    private static void recordAssault(ServerLevel level, ServerPlayer player, LivingEntity victim,
                                      CommunityKey community, float damage, long gameTime) {
        MinecraftServer server = level.getServer();
        int coalesce = McaReputationConfig.assaultCoalesceTicks();

        // Coalescing: a sustained beating is one deed, not one per damage tick (§20.1). A sliding
        // window keyed on the first qualifying hit is used rather than a world-time modulus, because a
        // modulus bucket lets a player split a beating across a boundary for a second full penalty.
        Optional<IncidentRecord> ongoing = ReputationService.findRecent(server, player.getUUID(), community,
                BuiltinIncidents.VILLAGER_ASSAULTED, victim.getUUID(), gameTime, coalesce);
        if (ongoing.isPresent()) {
            IncidentRecord incident = ongoing.get();
            float accumulated = incident.context(BuiltinIncidents.CONTEXT_DAMAGE)
                    .map(ReputationGameplayEvents::parseFloat).orElse(0.0f) + damage;
            int hits = incident.context(BuiltinIncidents.CONTEXT_HITS)
                    .map(ReputationGameplayEvents::parseInt).orElse(1) + 1;
            incident.putContext(BuiltinIncidents.CONTEXT_DAMAGE, String.format(java.util.Locale.ROOT,
                    "%.1f", accumulated));
            incident.putContext(BuiltinIncidents.CONTEXT_HITS, Integer.toString(hits));
            incident.touch(gameTime);
            ReputationSavedData.get(server).setDirty();
            return;
        }

        boolean selfDefence = AssaultTracker.villagerStruckFirst(victim.getUUID(), player.getUUID(),
                gameTime, McaReputationConfig.selfDefenseWindowTicks());
        Set<UUID> witnesses = WitnessResolver.resolve(level, victim.position(), player, victim, true);

        ReputationRequest.Builder request = ReputationRequest
                .builder(server, player.getUUID(), community, BuiltinIncidents.VILLAGER_ASSAULTED,
                        BuiltinIncidents.SOURCE_CORE)
                // Exact tick, not a modulus bucket: the sliding window above is the real coalescer, so
                // a new record always starts a fresh window and this key only has to absorb the same
                // damage event firing twice in one tick. A bucket key would wrongly swallow a genuine
                // second assault as DUPLICATE if the first incident had been pruned meanwhile.
                .dedupeKey("assault:" + player.getUUID() + ":" + victim.getUUID() + ":" + gameTime)
                .subject(IncidentSubject.villager(victim.getUUID(),
                        McaCompat.villagerName(victim).orElse(""), "victim"))
                .witnesses(witnesses)
                .context(BuiltinIncidents.CONTEXT_DAMAGE,
                        String.format(java.util.Locale.ROOT, "%.1f", damage))
                .context(BuiltinIncidents.CONTEXT_HITS, "1")
                .gameTime(gameTime);

        if (selfDefence) {
            // Reduce rather than exempt: striking back in a village square is still a public brawl.
            int base = IncidentRegistry.getOrUnknown(BuiltinIncidents.VILLAGER_ASSAULTED).defaultDelta();
            int reduced = (int) (base * McaReputationConfig.selfDefenseMultiplier()); // truncates toward zero
            request.delta(reduced).context(BuiltinIncidents.CONTEXT_SELF_DEFENCE, "true");
        }

        ReputationService.record(request.build());
        // After the record: the community record now exists, so the cached name/centre actually land —
        // caching first against a brand-new village wrote into nothing (§12.3).
        cacheMetadata(level, player.getUUID(), community, gameTime);
    }

    // ------------------------------------------------------------------
    // Killing
    // ------------------------------------------------------------------

    /**
     * Records a villager's death at a player's hand, upgrading any assault that led up to it.
     *
     * <p>The upgrade is the whole subtlety of §20.2. An assault worth {@code -8} followed by a killing
     * worth {@code -40} must total {@code -40}, not {@code -48}: the beating and the death are one
     * event from the village's point of view. The assault's contribution is therefore folded into the
     * killing — its record stays in the ledger as chronology with zero weight, linked both ways — and
     * the killing carries the full figure.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        if (!McaReputationConfig.enabled() || !McaReputationConfig.coreKillingEnabled()) {
            return;
        }
        // See onLivingHurt: stepping aside here also skips the assault-into-kill fold, which is
        // correct -- with an external authority there is no assault of ours to fold.
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_KILL)) {
            return;
        }
        if (!McaCompat.isMcaVillager(victim)) {
            return;
        }
        Optional<ServerPlayer> responsible = attribute(event.getSource());
        if (responsible.isEmpty()) {
            return;
        }
        ServerPlayer player = responsible.get();
        Optional<CommunityKey> maybeCommunity = CommunityResolver.resolve(victim);
        if (maybeCommunity.isEmpty()) {
            return;
        }
        CommunityKey community = maybeCommunity.get();
        MinecraftServer server = level.getServer();
        long gameTime = server.overworld().getGameTime();

        Optional<IncidentRecord> precursor = ReputationService.findRecent(server, player.getUUID(), community,
                BuiltinIncidents.VILLAGER_ASSAULTED, victim.getUUID(), gameTime,
                McaReputationConfig.assaultCoalesceTicks());

        Set<UUID> priorWitnesses = precursor.map(IncidentRecord::witnesses).orElseGet(Set::of);
        Set<UUID> witnesses = WitnessResolver.resolveForDeath(level, victim.position(), player, victim,
                priorWitnesses);

        ReputationRequest.Builder request = ReputationRequest
                .builder(server, player.getUUID(), community, BuiltinIncidents.VILLAGER_KILLED,
                        BuiltinIncidents.SOURCE_CORE)
                // Terminal key of the same logical group as the assault, so a duplicated death event
                // cannot charge twice while the assault's own key stays distinct (§14.2).
                .dedupeKey("assault:" + player.getUUID() + ":" + victim.getUUID() + ":killed")
                .subject(IncidentSubject.villager(victim.getUUID(),
                        McaCompat.villagerName(victim).orElse(""), "victim"))
                .witnesses(witnesses)
                .gameTime(gameTime);
        precursor.ifPresent(assault -> request.context(BuiltinIncidents.CONTEXT_PRECURSOR,
                assault.id().toString()));

        // Fold the assault BEFORE recording the killing, so the killing's own delta lands on a ledger
        // that no longer double-counts the lead-up and the resulting score is the killing's target.
        // The pre-fold contribution is snapshotted first: if the killing turns out to carry no public
        // weight — refused, duplicate, or retained unwitnessed because nobody saw the death — the fold
        // is rolled back. The village saw the beating; an unseen murder must not refund its penalty.
        Optional<IncidentRecord> foldedAssault = precursor.filter(IncidentRecord::contributes);
        int foldedSettled = foldedAssault.map(IncidentRecord::settledDelta).orElse(0);
        int foldedCurrent = foldedAssault.map(IncidentRecord::currentContribution).orElse(0);
        foldedAssault.ifPresent(assault -> {
            assault.foldInto(null, gameTime);
            recomputeCommunityScore(server, player.getUUID(), community);
        });

        ReputationResult result = ReputationService.record(request.build());

        boolean killCarriesWeight = result.applied() && result.incidentId()
                .flatMap(id -> ReputationService.incident(server, player.getUUID(), community, id))
                .map(IncidentRecord::contributes)
                .orElse(false);
        if (killCarriesWeight) {
            // Link the folded assault to the killing that absorbed it, now that the id exists.
            result.incidentId().ifPresent(killingId -> precursor.ifPresent(assault ->
                    assault.putContext(BuiltinIncidents.CONTEXT_SUPERSEDED_BY, killingId.toString())));
        } else {
            foldedAssault.ifPresent(assault -> {
                assault.restoreContribution(foldedSettled, foldedCurrent, gameTime);
                recomputeCommunityScore(server, player.getUUID(), community);
            });
        }
        if (foldedAssault.isPresent() || result.applied()) {
            // The fold (and any rollback of it) mutates the store outside the transaction, so it needs
            // its own dirty mark — "only when the kill applied" would lose a fold on a crash.
            ReputationSavedData.get(server).setDirty();
        }
        cacheMetadata(level, player.getUUID(), community, gameTime);
    }

    private static void recomputeCommunityScore(MinecraftServer server, UUID playerId, CommunityKey community) {
        ReputationSavedData.get(server).player(playerId)
                .flatMap(record -> record.community(community))
                .ifPresent(record -> record.recomputeScore(McaReputationConfig.minimumScore(),
                        McaReputationConfig.maximumScore()));
    }

    // ------------------------------------------------------------------
    // Attribution
    // ------------------------------------------------------------------

    /**
     * Which player, if any, is responsible for this damage (§20.1).
     *
     * <p>{@code DamageSource#getEntity} already resolves the <em>owner</em> of indirect damage — the
     * shooter of an arrow, the thrower of a potion — so the direct, projectile, and thrown-potion
     * cases need no special handling. Tamed animals are different: their owner is not the damage
     * source's entity, so they are resolved explicitly through {@link OwnableEntity} and only when
     * {@code attributeTamedDamage} is on.
     *
     * <p>Anything else — a mob, an explosion the player did not cause, environmental damage — returns
     * empty. §20.1 is explicit that unprovable attribution must fall through to "nobody", never to a
     * guess: blaming the wrong player is worse than missing a deed.
     */
    private static Optional<ServerPlayer> attribute(DamageSource source) {
        if (source == null) {
            return Optional.empty();
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            return Optional.of(player);
        }
        if (!McaReputationConfig.attributeTamedDamage()) {
            return Optional.empty();
        }
        Optional<ServerPlayer> viaOwner = ownerOf(source.getEntity());
        return viaOwner.isPresent() ? viaOwner : ownerOf(source.getDirectEntity());
    }

    private static Optional<ServerPlayer> ownerOf(Entity entity) {
        if (entity instanceof OwnableEntity ownable && ownable.getOwner() instanceof ServerPlayer owner) {
            return Optional.of(owner);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Brings a returning player's decay up to date and lets the optional legacy import run.
     *
     * <p>Login is the natural moment for both: it is exactly when someone is about to look at their
     * standing, and it costs one pass over one player's ledger.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.server;
        long gameTime = server.overworld().getGameTime();
        try {
            ReputationSavedData data = ReputationSavedData.get(server);
            data.player(player.getUUID())
                    .ifPresent(record -> record.setLastKnownName(player.getGameProfile().getName()));
            ReputationService.reconcile(server, player.getUUID(), gameTime);
            LegacyImportProviders.runFor(server, player, false);
        } catch (Throwable t) {
            McaReputation.LOGGER.error("[MCA: Reputation] login handling failed for {}; play continues",
                    player.getGameProfile().getName(), t);
        }
    }

    /**
     * The one periodic task this mod runs: a bounded reconciliation of <b>online players only</b>,
     * every {@code reconcileOnlineIntervalTicks} (default 1200 = one minute).
     *
     * <p>This is not a world scan and must never become one. It touches no village, no unloaded
     * player, and no entity — just the ledgers of whoever is currently connected, so an idle server
     * with nobody online does nothing at all (§34).
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // No phase check: the Post subtype already is the end phase.
        if (!McaReputationConfig.enabled()) {
            return;
        }
        if (++tickCounter < McaReputationConfig.reconcileOnlineIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.isEmpty()) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        AssaultTracker.sweep(gameTime, McaReputationConfig.selfDefenseWindowTicks());
        for (ServerPlayer player : online) {
            try {
                ReputationService.reconcile(server, player.getUUID(), gameTime);
            } catch (Throwable t) {
                McaReputation.LOGGER.debug("[MCA: Reputation] periodic reconcile failed for {}",
                        player.getGameProfile().getName(), t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Refreshes the cached village name and centre so history stays readable if the village goes. */
    private static void cacheMetadata(ServerLevel level, UUID playerId, CommunityKey community, long gameTime) {
        CommunityMetadata fresh = CommunityResolver.readMetadata(level, community, gameTime);
        if (fresh != CommunityMetadata.EMPTY) {
            ReputationService.cacheCommunityMetadata(level.getServer(), playerId, community, fresh);
        }
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Resets the periodic counter — on server stop, and between harness runs. */
    public static void resetTickCounter() {
        tickCounter = 0;
    }
}
