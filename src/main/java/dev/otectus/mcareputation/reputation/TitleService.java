package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.event.ReputationTitleGrantedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * Granting, revoking, and querying earned titles (spec §17.4).
 *
 * <p>Titles are <b>badges, not readouts</b>. Reaching Honored grants the Honored title; falling back
 * to Friend does not take it away, because it records that you once stood that well with these
 * people. A datapack may opt a title into {@code revocable}, and no shipped title does.
 *
 * <p>Grants are idempotent and post {@link ReputationTitleGrantedEvent} only on a genuinely new one,
 * so re-asserting a tier title on every login or Journal sync is free and silent.
 */
public final class TitleService {

    private TitleService() {
    }

    /**
     * Grants a community-scoped title.
     *
     * @return true when newly granted (and therefore when an event was posted)
     */
    public static boolean grantVillage(MinecraftServer server, UUID playerId, @Nullable ServerPlayer player,
                                       CommunityKey community, ResourceLocation title) {
        if (server == null) {
            return false;
        }
        return grantVillage(ServiceContext.of(server), playerId, player, community, title);
    }

    static boolean grantVillage(ServiceContext ctx, UUID playerId, @Nullable ServerPlayer player,
                                CommunityKey community, ResourceLocation title) {
        if (ctx == null || playerId == null || community == null || title == null) {
            return false;
        }
        ReputationSavedData data = ctx.data();
        CommunityReputationRecord record = data.getOrCreatePlayer(playerId).getOrCreate(community);
        if (!record.grantTitle(title)) {
            return false;
        }
        data.setDirty();
        McaReputation.LOGGER.debug("[MCA: Reputation] granted village title {} to {} in {}",
                title, playerId, community.asString());
        // Contained like every other §18 event: a tier-title grant runs *inside* the transaction, so a
        // listener that throws here would otherwise unwind a commit that has already happened and make
        // record() report ERROR for a change that stands.
        ReputationService.postSafely(ctx,
                new ReputationTitleGrantedEvent(playerId, player, community, title, TitleScope.VILLAGE));
        return true;
    }

    /**
     * Grants a global title.
     *
     * @return true when newly granted
     */
    public static boolean grantGlobal(MinecraftServer server, UUID playerId, @Nullable ServerPlayer player,
                                      ResourceLocation title) {
        if (server == null) {
            return false;
        }
        return grantGlobal(ServiceContext.of(server), playerId, player, title);
    }

    static boolean grantGlobal(ServiceContext ctx, UUID playerId, @Nullable ServerPlayer player,
                               ResourceLocation title) {
        if (ctx == null || playerId == null || title == null) {
            return false;
        }
        ReputationSavedData data = ctx.data();
        if (!data.getOrCreatePlayer(playerId).grantGlobalTitle(title)) {
            return false;
        }
        data.setDirty();
        McaReputation.LOGGER.debug("[MCA: Reputation] granted global title {} to {}", title, playerId);
        // Global titles have no community to hang off the score mirror, so they are mirrored here.
        // Failures are contained for the same reason as in the transaction service: a broken add-on
        // must not cost the player a title they have already earned.
        for (var mirror : ReputationService.mirrors()) {
            try {
                mirror.mirrorGlobalTitle(playerId, title);
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] mirror '{}' threw on a global title grant; "
                        + "the grant stands", mirror.mirrorName(), t);
            }
        }
        ReputationService.postSafely(ctx,
                new ReputationTitleGrantedEvent(playerId, player, null, title, TitleScope.GLOBAL));
        return true;
    }

    /**
     * Grants a title into whichever scope its definition declares, defaulting to
     * {@link TitleScope#VILLAGE}. A global title granted through this path ignores the community.
     */
    public static boolean grant(MinecraftServer server, UUID playerId, @Nullable ServerPlayer player,
                                @Nullable CommunityKey community, ResourceLocation title) {
        if (server == null) {
            return false;
        }
        return grant(ServiceContext.of(server), playerId, player, community, title);
    }

    static boolean grant(ServiceContext ctx, UUID playerId, @Nullable ServerPlayer player,
                         @Nullable CommunityKey community, ResourceLocation title) {
        if (Titles.scopeOf(title) == TitleScope.GLOBAL) {
            return grantGlobal(ctx, playerId, player, title);
        }
        return community != null && grantVillage(ctx, playerId, player, community, title);
    }

    /** Removes a title. No event is posted for a revocation; nothing has been earned. */
    public static boolean revoke(MinecraftServer server, UUID playerId, @Nullable CommunityKey community,
                                 ResourceLocation title) {
        if (server == null) {
            return false;
        }
        return revoke(ServiceContext.of(server), playerId, community, title);
    }

    static boolean revoke(ServiceContext ctx, UUID playerId, @Nullable CommunityKey community,
                          ResourceLocation title) {
        if (ctx == null || playerId == null || title == null) {
            return false;
        }
        ReputationSavedData data = ctx.data();
        PlayerReputationRecord record = data.player(playerId).orElse(null);
        if (record == null) {
            return false;
        }
        boolean removed = community == null
                ? record.revokeGlobalTitle(title)
                : record.community(community).map(c -> c.revokeTitle(title)).orElse(false);
        if (removed) {
            data.setDirty();
            McaReputation.LOGGER.info("[MCA: Reputation] revoked title {} from {}{}", title, playerId,
                    community == null ? " (global)" : " in " + community.asString());
        }
        return removed;
    }

    public static boolean hasTitle(MinecraftServer server, UUID playerId, ResourceLocation title,
                                   @Nullable CommunityKey community) {
        if (server == null || playerId == null || title == null) {
            return false;
        }
        return ReputationSavedData.get(server).player(playerId).map(record -> {
            if (record.hasGlobalTitle(title)) {
                return true;
            }
            if (community == null) {
                // No community named: "does the player hold this anywhere" is the useful question,
                // and it is what a dialogue condition without a village context means.
                return record.communities().stream().anyMatch(c -> c.hasTitle(title));
            }
            return record.community(community).map(c -> c.hasTitle(title)).orElse(false);
        }).orElse(false);
    }

    public static Set<ResourceLocation> globalTitles(MinecraftServer server, UUID playerId) {
        return ReputationSavedData.get(server).player(playerId)
                .map(PlayerReputationRecord::globalTitles)
                .orElseGet(Set::of);
    }

    public static Set<ResourceLocation> villageTitles(MinecraftServer server, UUID playerId,
                                                      CommunityKey community) {
        return ReputationSavedData.get(server).player(playerId)
                .flatMap(record -> record.community(community))
                .map(CommunityReputationRecord::titles)
                .orElseGet(Set::of);
    }
}
