package dev.otectus.mcareputation.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

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
public final class TierReachedTrigger extends SimpleCriterionTrigger<TierReachedTrigger.Instance> {

    /** Registered once from common setup; advancement triggers are singletons keyed by id. */
    public static final TierReachedTrigger INSTANCE = new TierReachedTrigger();

    public static final ResourceLocation ID = new ResourceLocation(McaReputation.MOD_ID, "tier_reached");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        Optional<String> tier = json.has("tier")
                ? Optional.of(GsonHelper.getAsString(json, "tier")).filter(value -> !value.isBlank())
                : Optional.empty();
        Optional<CommunityKey> community = Optional.empty();
        if (json.has("community")) {
            String raw = GsonHelper.getAsString(json, "community");
            community = Optional.of(CommunityKey.tryParse(raw).orElseThrow(() -> new JsonSyntaxException(
                    "Invalid community in mcareputation:tier_reached: '" + raw
                            + "'; expected '<dimension>/<villageId>'")));
        }
        return new Instance(player, tier, community, GsonHelper.getAsBoolean(json, "upward_only", true));
    }

    /**
     * Everything this trigger decides, with no player, level, or registry in sight. It lives here
     * rather than only on {@link Instance} because {@code ContextAwarePredicate} class-initialises
     * against the loot registries, which a plain unit test has no business loading.
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

    /** Called from the FORGE-bus listener for an online player whose tier just moved. */
    public void trigger(ServerPlayer player, String newTierId, CommunityKey community, boolean upward) {
        trigger(player, instance -> instance.matches(newTierId, community, upward));
    }

    public static final class Instance extends AbstractCriterionTriggerInstance {

        private final Optional<String> tier;
        private final Optional<CommunityKey> community;
        private final boolean upwardOnly;

        public Instance(ContextAwarePredicate player, Optional<String> tier,
                        Optional<CommunityKey> community, boolean upwardOnly) {
            super(ID, player);
            this.tier = tier;
            this.community = community;
            this.upwardOnly = upwardOnly;
        }

        public boolean matches(String newTierId, CommunityKey changed, boolean upward) {
            return TierReachedTrigger.matches(tier, community, upwardOnly, newTierId, changed, upward);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            tier.ifPresent(value -> json.addProperty("tier", value));
            community.ifPresent(value -> json.addProperty("community", value.asString()));
            json.addProperty("upward_only", upwardOnly);
            return json;
        }
    }
}
