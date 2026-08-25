package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.TitleScope;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Posted when a player newly earns a title (spec §26.5).
 *
 * <p>Only on a <em>new</em> grant. Title grants are idempotent (§17.4), and re-granting something the
 * player already holds posts nothing — which is what lets a tier title be re-asserted on every login
 * or Journal sync without spamming listeners or title-chain quests.
 *
 * <p>{@link #community} is present for {@link TitleScope#VILLAGE} titles and empty for global ones.
 */
public final class ReputationTitleGrantedEvent extends ReputationEvent {

    private final ResourceLocation title;
    private final TitleScope scope;

    public ReputationTitleGrantedEvent(UUID playerId, @Nullable ServerPlayer player,
                                       @Nullable CommunityKey community, ResourceLocation title,
                                       TitleScope scope) {
        super(playerId, player, community);
        this.title = title;
        this.scope = scope;
    }

    public ResourceLocation title() {
        return title;
    }

    public TitleScope scope() {
        return scope;
    }

    /** Empty for a global title. */
    public Optional<CommunityKey> communityOrEmpty() {
        return Optional.ofNullable(community());
    }
}
