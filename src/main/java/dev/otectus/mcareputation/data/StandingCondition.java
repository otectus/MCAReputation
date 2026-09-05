package dev.otectus.mcareputation.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.McaReputationMod;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The {@code mcareputation:standing} loot condition (spec §30.2): a datapack gate on how a village
 * sees a player. It works anywhere a {@code LootItemCondition} does — loot table pools, item
 * modifiers, and {@code predicates/} files, which is how an advancement criterion gates on standing.
 *
 * <h2>Shape</h2>
 *
 * <pre>{@code
 * { "condition": "mcareputation:standing",
 *   "community": "here",            // or "<dimension>/<villageId>"; default "here"
 *   "player": "this",               // or "killer"; default "this"
 *   "min": 20, "max": 80,           // score bounds, both optional
 *   "min_tier": "friend", "max_tier": "revered",
 *   "has_title": "mcareputation:village_hero" }
 * }</pre>
 *
 * <p>Every standing field is optional and ANDed by {@link ReputationQuery}, so an empty block is a
 * deliberate no-op rather than a parse error. {@code community} and {@code player} <em>are</em>
 * validated at parse time, because a typo in either is a silently always-false gate that a pack
 * author would have no way to find.
 *
 * <p>At runtime the condition answers false rather than throwing for every unknown: no entity in the
 * context, an entity that is not a player, or a position that belongs to no village. Loot is rolled
 * in plenty of places that have nothing to do with villages, and "no answer" must not break a table.
 *
 * @since MCA: Reputation 0.4.0
 */
public final class StandingCondition implements LootItemCondition {

    /** JSON value of {@code community} meaning "whichever village this happened in". */
    static final String COMMUNITY_HERE = "here";
    /** JSON value of {@code player} meaning the context's own entity. */
    static final String PLAYER_THIS = "this";
    /** JSON value of {@code player} meaning whatever killed the context's entity. */
    static final String PLAYER_KILLER = "killer";

    /**
     * {@code community}: empty for {@code "here"} (the default), otherwise the explicit key in the
     * same {@code <dimension>/<villageId>} form the command argument takes.
     */
    private static final Codec<Optional<CommunityKey>> COMMUNITY_CODEC = Codec.STRING.comapFlatMap(
            raw -> COMMUNITY_HERE.equals(raw)
                    ? DataResult.success(Optional.<CommunityKey>empty())
                    : CommunityKey.tryParse(raw)
                            .map(key -> DataResult.success(Optional.of(key)))
                            .orElseGet(() -> DataResult.error(() ->
                                    "Invalid community in mcareputation:standing: '" + raw
                                            + "'; expected 'here' or '<dimension>/<villageId>'")),
            community -> community.map(CommunityKey::asString).orElse(COMMUNITY_HERE));

    /** {@code player}: true when the killer is meant, false for the context's own entity. */
    private static final Codec<Boolean> PLAYER_SOURCE_CODEC = Codec.STRING.comapFlatMap(
            raw -> {
                if (PLAYER_THIS.equals(raw)) {
                    return DataResult.success(Boolean.FALSE);
                }
                if (PLAYER_KILLER.equals(raw)) {
                    return DataResult.success(Boolean.TRUE);
                }
                return DataResult.error(() -> "Invalid player in mcareputation:standing: '" + raw
                        + "'; expected '" + PLAYER_THIS + "' or '" + PLAYER_KILLER + "'");
            },
            useKiller -> useKiller ? PLAYER_KILLER : PLAYER_THIS);

    /**
     * The five standing fields plus the two validated ones. All are optional; a block with none of
     * them yields {@link ReputationQuery#ANY}, which is the deliberate no-op.
     */
    public static final MapCodec<StandingCondition> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                            Codec.INT.optionalFieldOf("min")
                                    .forGetter(condition -> box(condition.query.min())),
                            Codec.INT.optionalFieldOf("max")
                                    .forGetter(condition -> box(condition.query.max())),
                            Codec.STRING.optionalFieldOf("min_tier")
                                    .forGetter(condition -> condition.query.minTier()),
                            Codec.STRING.optionalFieldOf("max_tier")
                                    .forGetter(condition -> condition.query.maxTier()),
                            ResourceLocation.CODEC.optionalFieldOf("has_title")
                                    .forGetter(condition -> condition.query.hasTitle()),
                            COMMUNITY_CODEC.optionalFieldOf("community", Optional.empty())
                                    .forGetter(condition -> condition.community),
                            PLAYER_SOURCE_CODEC.optionalFieldOf("player", Boolean.FALSE)
                                    .forGetter(condition -> condition.useKiller))
                    .apply(instance, StandingCondition::new));

    private final ReputationQuery query;
    /** Empty means {@link #COMMUNITY_HERE}: resolve the nearest village at runtime. */
    private final Optional<CommunityKey> community;
    private final boolean useKiller;

    StandingCondition(ReputationQuery query, Optional<CommunityKey> community, boolean useKiller) {
        this.query = query;
        this.community = community;
        this.useKiller = useKiller;
    }

    private StandingCondition(Optional<Integer> min, Optional<Integer> max, Optional<String> minTier,
                              Optional<String> maxTier, Optional<ResourceLocation> hasTitle,
                              Optional<CommunityKey> community, boolean useKiller) {
        this(new ReputationQuery(unbox(min), unbox(max), blankAsAbsent(minTier), blankAsAbsent(maxTier),
                hasTitle), community, useKiller);
    }

    @Override
    public LootItemConditionType getType() {
        return McaReputationMod.STANDING_CONDITION.get();
    }

    @Override
    public boolean test(LootContext context) {
        // 1.21 renamed KILLER_ENTITY to ATTACKING_ENTITY; the JSON value stays "killer" so packs
        // written against the Forge build keep parsing.
        Entity entity = context.getParamOrNull(
                useKiller ? LootContextParams.ATTACKING_ENTITY : LootContextParams.THIS_ENTITY);
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        ServerLevel level = context.getLevel();
        Optional<CommunityKey> resolved = community.isPresent()
                ? community
                : CommunityResolver.resolveNearest(level, originOf(context, entity));
        if (resolved.isEmpty()) {
            return false; // nowhere near a village: there is nobody here to have standing with
        }
        return McaReputationApi.matches(level.getServer(), player.getUUID(), resolved.get(), query);
    }

    /** Where the deed happened: the table's own origin when it has one, otherwise the player. */
    private static BlockPos originOf(LootContext context, Entity entity) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        return BlockPos.containing(origin != null ? origin : entity.position());
    }

    // ------------------------------------------------------------------
    // Parsing helpers - the codec speaks Optional<Integer>, the query OptionalInt
    // ------------------------------------------------------------------

    private static Optional<Integer> box(OptionalInt value) {
        return value.isPresent() ? Optional.of(value.getAsInt()) : Optional.empty();
    }

    private static OptionalInt unbox(Optional<Integer> value) {
        return value.map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    /** A blank tier id is no tier bound at all, as the 1.20.1 Gson parser treated it. */
    private static Optional<String> blankAsAbsent(Optional<String> value) {
        return value.filter(raw -> !raw.isBlank());
    }

    /** The query this condition tests; package-private for the parse tests. */
    ReputationQuery query() {
        return query;
    }

    /** The fixed community, or empty for {@code "here"}; package-private for the parse tests. */
    Optional<CommunityKey> community() {
        return community;
    }

    /** Whether the killer rather than the context entity is asked about; for the parse tests. */
    boolean usesKiller() {
        return useKiller;
    }
}
