package dev.otectus.mcareputation.reputation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * The display definition of an earned title (spec §22.2), loaded from
 * {@code data/<ns>/mcareputation/titles/**&#47;*.json} and, for backwards compatibility, from
 * {@code data/<ns>/mcaquests/titles/**&#47;*.json}.
 *
 * <p>Titles work even when undefined — ownership is recorded against the id, and an unknown id simply
 * displays as itself. A definition adds a name, a description, an icon, and the declared scope for
 * validation. This asymmetry is deliberate: removing a datapack must never revoke something a player
 * earned (§17.4).
 *
 * <p>{@code revocable} exists so a future pack can declare a badge that is lost when standing falls.
 * No shipped title uses it, and tier-granted titles stay earned by default: a title is a record of
 * something you once did, not a live readout of where you stand.
 */
public record TitleDefinition(
        Component name,
        Optional<Component> description,
        TitleScope scope,
        boolean revocable,
        Optional<ResourceLocation> icon) {

    public static final Codec<TitleDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // Bare-string names are accepted for the same legacy reason as tier names (§22.1).
            ExtraCodecs.COMPONENT.fieldOf("name").forGetter(TitleDefinition::name),
            StrictCodecs.strictOptional(ExtraCodecs.COMPONENT, "description").forGetter(TitleDefinition::description),
            StrictCodecs.strictOptional(TitleScope.CODEC, "scope", TitleScope.VILLAGE).forGetter(TitleDefinition::scope),
            StrictCodecs.strictOptional(Codec.BOOL, "revocable", false).forGetter(TitleDefinition::revocable),
            StrictCodecs.strictOptional(ResourceLocation.CODEC, "icon").forGetter(TitleDefinition::icon)
    ).apply(instance, TitleDefinition::new));

    public TitleDefinition {
        description = description == null ? Optional.empty() : description;
        scope = scope == null ? TitleScope.VILLAGE : scope;
        icon = icon == null ? Optional.empty() : icon;
    }

    /**
     * The icon stack for the UI. A missing or unregistered item falls back to a name tag rather than
     * invalidating the title — §22.2 is explicit that a bad icon must never affect ownership.
     *
     * <p>Resolves against the item registry, so this must only be called on a side where registries
     * are populated (i.e. from client rendering, not from a codec).
     */
    public ItemStack iconStack() {
        return icon.map(id -> {
                    Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
                    return item == null || item == Items.AIR ? new ItemStack(Items.NAME_TAG) : new ItemStack(item);
                })
                .orElseGet(() -> new ItemStack(Items.NAME_TAG));
    }

    /** A stand-in for a title the player owns but no datapack describes. */
    public static TitleDefinition unknown(ResourceLocation id) {
        return new TitleDefinition(
                Component.literal(id.getPath().replace('_', ' ')),
                Optional.empty(),
                TitleScope.VILLAGE,
                false,
                Optional.empty());
    }
}
