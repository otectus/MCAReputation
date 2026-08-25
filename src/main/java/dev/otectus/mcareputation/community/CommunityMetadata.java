package dev.otectus.mcareputation.community;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * The last-known presentation facts about a community (spec §12.3), cached alongside its score so a
 * village that is unloaded, renamed, or outright deleted still displays sensibly.
 *
 * <p>Identity never comes from here — that is {@link CommunityKey}'s job alone. Renaming a village
 * updates this record and changes nothing about the player's standing; deleting a village leaves the
 * history intact with the last name we saw.
 *
 * <p>{@code name} may be blank (village never resolved, or MCA returned nothing), in which case
 * {@link #displayName} falls back to a localized {@code Village #<id>} label that includes the
 * dimension when it is not the overworld.
 */
public record CommunityMetadata(String name, Optional<BlockPos> center, long lastResolvedGameTime) {

    public static final CommunityMetadata EMPTY = new CommunityMetadata("", Optional.empty(), 0L);

    /** Village names are user-editable in MCA; bound what we are willing to cache and echo back. */
    public static final int MAX_NAME_LENGTH = 64;

    public CommunityMetadata {
        name = name == null ? "" : truncate(name);
        center = center == null ? Optional.empty() : center;
    }

    private static String truncate(String raw) {
        String trimmed = raw.strip();
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    public boolean hasName() {
        return !name.isEmpty();
    }

    /**
     * Merges freshly resolved live values over the cached ones. A live read that came back blank does
     * <em>not</em> erase a good cached name — §35.1 requires a missing village to keep its history.
     */
    public CommunityMetadata refreshed(String liveName, Optional<BlockPos> liveCenter, long gameTime) {
        String mergedName = liveName == null || liveName.isBlank() ? name : truncate(liveName);
        Optional<BlockPos> mergedCenter = liveCenter != null && liveCenter.isPresent() ? liveCenter : center;
        return new CommunityMetadata(mergedName, mergedCenter, Math.max(lastResolvedGameTime, gameTime));
    }

    /**
     * How this community should be labelled in UI, chat, and command output. Prefers the cached
     * village name; otherwise a localized fallback that stays unambiguous across dimensions.
     */
    public Component displayName(CommunityKey key) {
        if (hasName()) {
            return Component.literal(name);
        }
        boolean overworld = "minecraft:overworld".equals(key.dimension().toString());
        return overworld
                ? Component.translatable("mcareputation.community.unnamed", key.villageId())
                : Component.translatable("mcareputation.community.unnamed_in",
                        key.villageId(), key.dimension().toString());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (hasName()) {
            tag.putString("name", name);
        }
        center.ifPresent(pos -> tag.putLong("center", pos.asLong()));
        if (lastResolvedGameTime != 0L) {
            tag.putLong("seen", lastResolvedGameTime);
        }
        return tag;
    }

    /** Never throws: a malformed metadata compound degrades to {@link #EMPTY}, not a lost community. */
    public static CommunityMetadata load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        String name = tag.getString("name");
        Optional<BlockPos> center = tag.contains("center")
                ? Optional.of(BlockPos.of(tag.getLong("center")))
                : Optional.empty();
        return new CommunityMetadata(name, center, tag.getLong("seen"));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_NAME_LENGTH);
        // Explicit lambda: 1.21.1 added a static ByteBuf overload of writeBlockPos, making the
        // method reference ambiguous.
        buf.writeOptional(center, (b, value) -> b.writeBlockPos(value));
        buf.writeVarLong(Math.max(0L, lastResolvedGameTime));
    }

    public static CommunityMetadata read(FriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_NAME_LENGTH);
        Optional<BlockPos> center = buf.readOptional(b -> b.readBlockPos());
        return new CommunityMetadata(name, center, buf.readVarLong());
    }
}
