package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaCompat;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.WitnessResolver;
import dev.otectus.mcareputation.reputation.ReputationService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The four newest deeds: a villager saved, a villager cured, a raid seen through, and a
 * player killed where a village could watch.
 *
 * <p>A separate class from {@link ReputationGameplayEvents} on purpose. That one is the harm layer
 * and its two hooks are entangled — an assault folds into the killing that follows it. These four
 * are independent of each other and of it, so keeping them apart means neither file has to be read to
 * understand the other, and a deed can be switched off in config without touching assault detection.
 *
 * <p>Every hook follows the same gate order as §20's: cheap config reads, then the authority claim,
 * then any reflective MCA test. The claim is checked before the MCA question for the same reason it
 * is there — a claimed kind should cost a list read, not a reflective lookup.
 *
 * <p>Stateless by construction. The anti-farm rules live entirely in {@link DeedKeys}, which the
 * ledger's own dedupe then enforces, so nothing here has to be swept, reset, or reasoned about across
 * a server restart.
 *
 * @since MCA: Reputation 0.4.0
 */
@EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class ReputationDeedEvents {

    /** Vanilla's own NBT key for whoever fed the golden apple; written by {@code ZombieVillager}. */
    private static final String TAG_CONVERSION_PLAYER = "ConversionPlayer";

    private ReputationDeedEvents() {
    }

    // ------------------------------------------------------------------
    // Rescue
    // ------------------------------------------------------------------

    /**
     * Records killing the mob that was attacking a villager.
     *
     * <p>The hard part is not detecting the kill, it is deciding whether it was a rescue at all. Two
     * proofs are accepted: the mob was <em>currently</em> targeting a living MCA villager, or it had
     * struck one within {@code rescueThreatWindowTicks} and simply lost aggro on the way down. Both
     * additionally require the villager to be inside witness range of the mob — killing a creeper
     * across the valley from someone it once hit is not a rescue.
     *
     * <p>{@link EventPriority#LOWEST} so that a mod cancelling or rewriting the death has already
     * done so, matching the harm hooks.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onThreatKilled(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide() || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        if (!McaReputationConfig.enabled() || !McaReputationConfig.coreRescueEnabled()) {
            return;
        }
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_RESCUE)) {
            return;
        }
        if (!(dead instanceof Mob mob) || !(dead instanceof Enemy)) {
            return;
        }
        Optional<ServerPlayer> responsible = ReputationGameplayEvents.attribute(event.getSource());
        if (responsible.isEmpty()) {
            return;
        }
        Optional<LivingEntity> threatened = threatenedVillager(mob);
        if (threatened.isEmpty()) {
            return;
        }
        ServerPlayer player = responsible.get();
        LivingEntity villager = threatened.get();

        int radius = McaReputationConfig.witnessRadius();
        if (villager.distanceToSqr(mob) > (double) radius * radius) {
            return;
        }
        Optional<CommunityKey> maybeCommunity = CommunityResolver.resolve(villager);
        if (maybeCommunity.isEmpty()) {
            // No village to be grateful, exactly as for an assault on a stray villager (§12.2).
            return;
        }
        CommunityKey community = maybeCommunity.get();
        MinecraftServer server = level.getServer();
        long gameTime = server.overworld().getGameTime();

        Set<UUID> witnesses = WitnessResolver.resolve(level, villager.position(), player, villager, true);

        ReputationRequest request = ReputationRequest
                .builder(server, player.getUUID(), community, BuiltinIncidents.VILLAGER_RESCUED,
                        BuiltinIncidents.SOURCE_CORE)
                // A bucket, not a sliding window: a player who can restart the window by acting sooner
                // has a farm. Kiting the same skeleton back to the same villager pays once per bucket.
                .dedupeKey(DeedKeys.rescue(player.getUUID(), villager.getUUID(),
                        DeedKeys.bucket(gameTime, McaReputationConfig.rescueCoalesceTicks())))
                .subject(IncidentSubject.villager(villager.getUUID(),
                        McaCompat.villagerName(villager).orElse(""), "rescued"))
                .witnesses(witnesses)
                .context(BuiltinIncidents.CONTEXT_THREAT, EntityType.getKey(mob.getType()).toString())
                .gameTime(gameTime)
                .build();

        ReputationService.record(request);
        ReputationGameplayEvents.cacheMetadata(level, player.getUUID(), community, gameTime);
    }

    /** The villager this mob was a danger to, if the death can honestly be called a rescue. */
    private static Optional<LivingEntity> threatenedVillager(Mob mob) {
        LivingEntity target = mob.getTarget();
        if (McaCompat.isLivingMcaVillager(target)) {
            return Optional.of(target);
        }
        LivingEntity struck = mob.getLastHurtMob();
        if (McaCompat.isLivingMcaVillager(struck)
                && mob.tickCount - mob.getLastHurtMobTimestamp()
                        <= McaReputationConfig.rescueThreatWindowTicks()) {
            return Optional.of(struck);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Cure
    // ------------------------------------------------------------------

    /**
     * Records curing a zombie villager back into an MCA villager.
     *
     * <p>MCA's zombie villager extends vanilla's and lets vanilla {@code finishConversion} run, so
     * this event fires and the outcome is MCA's villager entity. Attribution comes from vanilla's own
     * {@code ConversionPlayer} tag on the zombie, read out of its NBT rather than through a field:
     * the cure can complete minutes after the golden apple, long past any window this mod could keep.
     *
     * <p>A curer who has logged out earns nothing. The alternative is recording against a player the
     * witness scan cannot place at the scene, and standing this mod cannot explain is worse than
     * standing it did not grant.
     */
    @SubscribeEvent
    public static void onVillagerCured(LivingConversionEvent.Post event) {
        LivingEntity zombie = event.getEntity();
        if (zombie.level().isClientSide() || !(zombie.level() instanceof ServerLevel level)) {
            return;
        }
        if (!McaReputationConfig.enabled() || !McaReputationConfig.coreCureEnabled()) {
            return;
        }
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_CURE)) {
            return;
        }
        if (!(zombie instanceof ZombieVillager)) {
            return;
        }
        LivingEntity outcome = event.getOutcome();
        if (!McaCompat.isMcaVillager(outcome)) {
            return;
        }
        Optional<UUID> curer = conversionPlayer(zombie);
        if (curer.isEmpty()) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(curer.get());
        if (player == null) {
            return;
        }
        Optional<CommunityKey> maybeCommunity = CommunityResolver.resolve(outcome);
        if (maybeCommunity.isEmpty()) {
            return;
        }
        CommunityKey community = maybeCommunity.get();
        MinecraftServer server = level.getServer();
        long gameTime = server.overworld().getGameTime();

        Set<UUID> witnesses = WitnessResolver.resolve(level, outcome.position(), player, outcome, true);

        ReputationRequest request = ReputationRequest
                .builder(server, player.getUUID(), community, BuiltinIncidents.VILLAGER_CURED,
                        BuiltinIncidents.SOURCE_CORE)
                // No time component at all: one villager can only be brought back once by one player,
                // so the pair is the whole identity of the deed.
                .dedupeKey(DeedKeys.cure(player.getUUID(), outcome.getUUID()))
                .subject(IncidentSubject.villager(outcome.getUUID(),
                        McaCompat.villagerName(outcome).orElse(""), "cured"))
                .witnesses(witnesses)
                .gameTime(gameTime)
                .build();

        ReputationService.record(request);
        ReputationGameplayEvents.cacheMetadata(level, player.getUUID(), community, gameTime);
    }

    /** Vanilla's record of who started the cure, read from the zombie's own saved data. */
    private static Optional<UUID> conversionPlayer(Entity zombie) {
        try {
            CompoundTag tag = new CompoundTag();
            zombie.saveWithoutId(tag);
            return tag.hasUUID(TAG_CONVERSION_PLAYER)
                    ? Optional.of(tag.getUUID(TAG_CONVERSION_PLAYER))
                    : Optional.empty();
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] could not read the curer from a zombie "
                    + "villager; the cure goes uncredited", t);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Raid
    // ------------------------------------------------------------------

    /**
     * Records a village surviving a raid, credited to whoever it made a hero.
     *
     * <p>The effect is the signal because vanilla only grants it to players the raid itself counted as
     * participants, which is a far better test of "helped" than any proximity check this mod could
     * write. Visibility is {@code village} rather than {@code witnessed}: everyone knows who held the
     * gate, whether or not they were looking at the moment it happened.
     *
     * <p>The effect id is compared <b>first</b>. This event fires for every effect applied to every
     * entity on the server, so anything more expensive above that check is a per-potion cost.
     */
    @SubscribeEvent
    public static void onHeroOfTheVillage(MobEffectEvent.Added event) {
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null || !instance.getEffect().is(MobEffects.HERO_OF_THE_VILLAGE)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!McaReputationConfig.enabled() || !McaReputationConfig.coreRaidEnabled()) {
            return;
        }
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_RAID_REPELLED)) {
            return;
        }
        BlockPos pos = player.blockPosition();
        Raid raid = level.getRaidAt(pos);
        if (raid == null || !raid.isVictory()) {
            return;
        }
        Optional<CommunityKey> maybeCommunity = CommunityResolver.resolveNearest(level, pos);
        if (maybeCommunity.isEmpty()) {
            // A vanilla village with no MCA community behind it: vanilla's own hero effect is the
            // whole reward, and this mod has no ledger to write into.
            return;
        }
        CommunityKey community = maybeCommunity.get();
        MinecraftServer server = level.getServer();
        long gameTime = server.overworld().getGameTime();

        CommunityMetadata metadata = CommunityResolver.readMetadata(level, community, gameTime);
        Set<UUID> witnesses = WitnessResolver.resolve(level, player.position(), player, null, false);

        ReputationRequest request = ReputationRequest
                .builder(server, player.getUUID(), community, BuiltinIncidents.RAID_REPELLED,
                        BuiltinIncidents.SOURCE_CORE)
                // Keyed on the raid, not the moment: hero of the village is re-applied every time it
                // is refreshed, and every one of those firings is the same victory.
                .dedupeKey(DeedKeys.raid(player.getUUID(), community.asString(), raid.getId()))
                .subject(IncidentSubject.community(metadata.name()))
                .witnesses(witnesses)
                .context(BuiltinIncidents.CONTEXT_RAID_ID, Integer.toString(raid.getId()))
                .gameTime(gameTime)
                .build();

        ReputationService.record(request);
        ReputationGameplayEvents.cacheMetadata(level, player.getUUID(), community, gameTime);
    }

    // ------------------------------------------------------------------
    // Player killing
    // ------------------------------------------------------------------

    /**
     * Records one player killing another where a village can see it. <b>Off by default</b> — on most
     * servers a duel is not the village's business, and turning it on is an operator's decision.
     *
     * <p>No self-defence modelling: unlike a villager, a player cannot be asked who swung first in a
     * way this mod could defend, so this release records the death plainly and leaves the judgement to
     * whoever reads the ledger.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerKilled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)
                || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        if (!McaReputationConfig.enabled() || !McaReputationConfig.corePvpEnabled()) {
            return;
        }
        if (CoreIncidentAuthorities.isClaimed(CoreIncidentKind.PLAYER_KILL_IN_VILLAGE)) {
            return;
        }
        Optional<ServerPlayer> responsible = ReputationGameplayEvents.attribute(event.getSource());
        if (responsible.isEmpty()) {
            return;
        }
        ServerPlayer killer = responsible.get();
        if (killer.getUUID().equals(victim.getUUID())) {
            return; // a player killed by their own arrow has wronged nobody
        }
        Optional<CommunityKey> maybeCommunity =
                CommunityResolver.resolveNearest(level, victim.blockPosition());
        if (maybeCommunity.isEmpty()) {
            // Away from any village this is a private matter, and the mod records nothing.
            return;
        }
        CommunityKey community = maybeCommunity.get();
        MinecraftServer server = level.getServer();
        long gameTime = server.overworld().getGameTime();

        String victimName = victim.getGameProfile().getName();
        Set<UUID> witnesses = WitnessResolver.resolve(level, victim.position(), killer, null, false);

        ReputationRequest request = ReputationRequest
                .builder(server, killer.getUUID(), community, BuiltinIncidents.PLAYER_KILLED_IN_VILLAGE,
                        BuiltinIncidents.SOURCE_CORE)
                // Exact tick, as the villager killing uses: only a duplicated death event has to be
                // absorbed, and two genuine kills in one tick are not a case worth protecting.
                .dedupeKey(DeedKeys.pvp(killer.getUUID(), victim.getUUID(), gameTime))
                .subject(IncidentSubject.player(victim.getUUID(), victimName, "victim"))
                .witnesses(witnesses)
                .context(BuiltinIncidents.CONTEXT_VICTIM_PLAYER, victimName)
                .gameTime(gameTime)
                .build();

        ReputationService.record(request);
        ReputationGameplayEvents.cacheMetadata(level, killer.getUUID(), community, gameTime);
    }
}
