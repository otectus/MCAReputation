package dev.otectus.mcareputation.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The {@code mcareputation:tier_reached} advancement trigger (spec §30.2): fires when a player's
 * standing with a community crosses a tier boundary.
 *
 * <h2>Shape</h2>
 *
 * <pre>{@code
 * { "trigger": "mcareputation:tier_reached",
 *   "conditions": { "tier": "friend",                    // optional; any tier when absent
 *                   "community": "minecraft:overworld/3", // optional; any community when absent
 *                   "upward_only": true } }               // optional; true when absent
 * }</pre>
 *
 * <p>{@code upward_only} defaults to true because the overwhelmingly common intent is "the player
 * earned this", and slipping back down into a tier would otherwise grant the same advancement. A pack
 * that wants to notice a fall sets it to false explicitly.
 *
 * <p>Score thresholds are deliberately not a field here. A criterion that needs one puts the
 * {@link StandingCondition} in its {@code player} predicate, so there is one implementation of what a
 * standing test means.
 *
 * @since MCA: Reputation 0.4.0
 */
public final class TierReachedTrigger extends SimpleCriterionTrigger<TierReachedTrigger.TriggerInstance> {

    /** Registered once from {@code McaReputationMod}; advancement triggers are singletons keyed by id. */
    public static final TierReachedTrigger INSTANCE = new TierReachedTrigger();

    public static final ResourceLocation ID = McaReputation.id("tier_reached");

    /** The {@code <dimension>/<villageId>} form the command argument and the loot condition take. */
    static final Codec<CommunityKey> COMMUNITY_CODEC = Codec.STRING.comapFlatMap(
            raw -> CommunityKey.tryParse(raw)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() ->
                            "Invalid community in mcareputation:tier_reached: '" + raw
                                    + "'; expected '<dimension>/<villageId>'")),
            CommunityKey::asString);

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /**
     * Everything this trigger decides, with no player, level, or registry in sight. It lives here
     * rather than only on {@link TriggerInstance} because {@code ContextAwarePredicate}
     * class-initialises against the loot registries, which a plain unit test has no business loading.
     */
    static boolean matches(Optional<String> tier, Optional<CommunityKey> community, boolean upwardOnly,
                           String newTierId, CommunityKey changed, boolean upward) {
        if (upwardOnly && !upward) {
            return false;
        }
        if (tier.isPresent() && !tier.get().equals(newTierId)) {
            return false;
        }
        return community.isEmpty() || community.get().equals(changed);
    }

    /** Called from the game-bus listener for an online player whose tier just moved. */
    public void trigger(ServerPlayer player, String newTierId, CommunityKey community, boolean upward) {
        trigger(player, instance -> instance.matches(newTierId, community, upward));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<String> tier,
                                  Optional<CommunityKey> community, boolean upwardOnly)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")
                                .forGetter(TriggerInstance::player),
                        Codec.STRING.optionalFieldOf("tier").forGetter(TriggerInstance::tier),
                        COMMUNITY_CODEC.optionalFieldOf("community").forGetter(TriggerInstance::community),
                        Codec.BOOL.optionalFieldOf("upward_only", true).forGetter(TriggerInstance::upwardOnly))
                .apply(instance, TriggerInstance::new));

        /** A blank tier id is the same as no tier id at all, as the Gson parser treated it. */
        public TriggerInstance {
            tier = tier == null ? Optional.empty() : tier.filter(value -> !value.isBlank());
        }

        public boolean matches(String newTierId, CommunityKey changed, boolean upward) {
            return TierReachedTrigger.matches(tier, community, upwardOnly, newTierId, changed, upward);
        }
    }
}
