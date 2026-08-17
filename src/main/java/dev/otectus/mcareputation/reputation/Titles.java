package dev.otectus.mcareputation.reputation;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live, immutable registry of title definitions, swapped atomically on datapack reload (§21.2).
 *
 * <p>As with {@link dev.otectus.mcareputation.incident.IncidentRegistry}, a missing definition is not
 * an error. Ownership of a title is recorded against its id in the player's saved data and is
 * independent of any pack: removing the datapack that described "Village Guardian" changes what the
 * badge looks like, never whether the player earned it (§17.4).
 */
public final class Titles {

    private static volatile Map<ResourceLocation, TitleDefinition> definitions = Map.of();

    private Titles() {
    }

    public static void replaceAll(Map<ResourceLocation, TitleDefinition> loaded) {
        definitions = Map.copyOf(loaded);
    }

    public static Optional<TitleDefinition> get(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(id));
    }

    /** The definition, or a placeholder derived from the id itself. Never fails. */
    public static TitleDefinition getOrUnknown(ResourceLocation id) {
        return get(id).orElseGet(() -> TitleDefinition.unknown(id));
    }

    public static boolean contains(ResourceLocation id) {
        return id != null && definitions.containsKey(id);
    }

    public static Set<ResourceLocation> ids() {
        return definitions.keySet();
    }

    public static Map<ResourceLocation, TitleDefinition> all() {
        return definitions;
    }

    /**
     * The declared scope of a title, defaulting to {@link TitleScope#VILLAGE} when undefined — the
     * scope a caller most likely meant, and the one Quests' own {@code TitleDefinition} defaults to.
     */
    public static TitleScope scopeOf(ResourceLocation id) {
        return get(id).map(TitleDefinition::scope).orElse(TitleScope.VILLAGE);
    }

    /**
     * Whether losing standing should take this title away. Defaults to {@code false}: a title is a
     * record of something achieved, and no shipped title is revocable (§17.4).
     */
    public static boolean isRevocable(ResourceLocation id) {
        return get(id).map(TitleDefinition::revocable).orElse(false);
    }
}
