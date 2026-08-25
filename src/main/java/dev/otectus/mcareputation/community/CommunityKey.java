package dev.otectus.mcareputation.community;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * The canonical identity of a community: a <b>dimension-aware</b> MCA village (spec §12.1).
 *
 * <p>MCA's {@code VillageManager} is a {@code SavedData} obtained per {@code ServerLevel}, so village
 * ids are allocated per dimension — village {@code 3} in the Nether and village {@code 3} in the
 * overworld are different places that would silently share a score under a bare integer key. Quests'
 * existing {@code "v:<id>"} scope strings have exactly that defect; §32.2 migrates them by assuming
 * {@code minecraft:overworld}, which is the only dimension the old key could have meant in practice.
 *
 * <p><b>Never persist or compare a community using only the integer id.</b> Every store, packet,
 * command argument, and API surface in this mod takes a whole {@code CommunityKey}.
 *
 * <p>Instances are immutable and safe to use as a map key. {@code villageId} must be non-negative and
 * {@code dimension} must be a parseable {@link ResourceLocation}; {@link #of} and {@link #tryParse}
 * enforce that and return empty rather than constructing a nonsense key.
 */
public record CommunityKey(ResourceLocation dimension, int villageId) implements Comparable<CommunityKey> {

    /** NBT/JSON key for the dimension half. */
    public static final String TAG_DIMENSION = "dim";
    /** NBT/JSON key for the village half. */
    public static final String TAG_VILLAGE = "village";

    /**
     * Codec used by packets, commands, and any datapack surface that names a community. Rejects a
     * negative village id at parse time so a malformed pack cannot introduce an impossible key.
     */
    public static final Codec<CommunityKey> CODEC = RecordCodecBuilder.<CommunityKey>create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf(TAG_DIMENSION).forGetter(CommunityKey::dimension),
            Codec.INT.fieldOf(TAG_VILLAGE).forGetter(CommunityKey::villageId)
    ).apply(instance, CommunityKey::new)).flatXmap(CommunityKey::validate, CommunityKey::validate);

    public CommunityKey {
        if (dimension == null) {
            throw new IllegalArgumentException("CommunityKey dimension must not be null");
        }
        if (villageId < 0) {
            throw new IllegalArgumentException("CommunityKey villageId must be non-negative, got " + villageId);
        }
    }

    private static DataResult<CommunityKey> validate(CommunityKey key) {
        return key.villageId < 0
                ? DataResult.error(() -> "Negative village id: " + key.villageId)
                : DataResult.success(key);
    }

    /** Guarded constructor: empty instead of throwing when the id is negative. */
    public static Optional<CommunityKey> of(ResourceLocation dimension, int villageId) {
        if (dimension == null || villageId < 0) {
            return Optional.empty();
        }
        return Optional.of(new CommunityKey(dimension, villageId));
    }

    /** Guarded constructor from a dimension {@link ResourceKey}, as levels expose it. */
    public static Optional<CommunityKey> of(ResourceKey<Level> dimension, int villageId) {
        return dimension == null ? Optional.empty() : of(dimension.location(), villageId);
    }

    /**
     * Parses the human/command form {@code <namespace>:<path>/<villageId>}, e.g.
     * {@code minecraft:overworld/3}. Empty for anything malformed — never throws.
     */
    public static Optional<CommunityKey> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int split = raw.lastIndexOf('/');
        if (split <= 0 || split == raw.length() - 1) {
            return Optional.empty();
        }
        ResourceLocation dimension = ResourceLocation.tryParse(raw.substring(0, split));
        if (dimension == null) {
            return Optional.empty();
        }
        try {
            return of(dimension, Integer.parseInt(raw.substring(split + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads a key from its own compound. Empty when the tag is absent, the dimension does not parse,
     * or the id is negative — §13.6 requires one malformed entry to be skipped, never to abort a load.
     */
    public static Optional<CommunityKey> load(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_VILLAGE)) {
            return Optional.empty();
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        return dimension == null ? Optional.empty() : of(dimension, tag.getInt(TAG_VILLAGE));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIMENSION, dimension.toString());
        tag.putInt(TAG_VILLAGE, villageId);
        return tag;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(villageId);
    }

    /**
     * Network read. A hostile/corrupt packet can carry a negative id, so this clamps to zero rather
     * than throwing inside the netty pipeline; the server re-validates the key against its known
     * communities before it is ever used (§27.2).
     */
    public static CommunityKey read(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        return new CommunityKey(dimension, Math.max(0, buf.readVarInt()));
    }

    /** The stable string form used for map keys in NBT, command suggestions, and logs. */
    public String asString() {
        return dimension + "/" + villageId;
    }

    /** Deterministic ordering (dimension, then id) so UI lists and witness caps never depend on hash order. */
    @Override
    public int compareTo(CommunityKey other) {
        int byDimension = dimension.compareTo(other.dimension);
        return byDimension != 0 ? byDimension : Integer.compare(villageId, other.villageId);
    }

    @Override
    public String toString() {
        return asString();
    }
}
