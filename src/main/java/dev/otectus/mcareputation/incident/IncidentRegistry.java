package dev.otectus.mcareputation.incident;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live, immutable registry of incident definitions, swapped in one assignment by the datapack
 * reload (§21.2). Readers see either the whole previous generation or the whole next one, never a
 * partially rebuilt map, so an in-flight transaction cannot observe a half-loaded pack.
 *
 * <h2>Unknown definitions are not errors</h2>
 *
 * <p>{@link #getOrUnknown} never fails. A world can outlive the datapack that defined a deed — a pack
 * is removed, a modpack updates, a server rolls back — and §13.6/§35.1 both require the stored record
 * and its contribution to survive that intact, with only the display degrading. Score is a property
 * of history, not of the currently installed packs, so a missing definition must never quietly change
 * anybody's standing.
 */
public final class IncidentRegistry {

    private static volatile Map<ResourceLocation, IncidentDefinition> definitions = Map.of();

    private IncidentRegistry() {
    }

    public static void replaceAll(Map<ResourceLocation, IncidentDefinition> loaded) {
        definitions = Map.copyOf(loaded);
    }

    /** The definition, or empty when no pack currently defines this id. */
    public static Optional<IncidentDefinition> get(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(id));
    }

    /**
     * The definition, or a safe placeholder describing the missing id. Use this on every read path
     * that has to produce a number or a line of text; use {@link #get} only where "does this type
     * exist" is genuinely the question, such as validating a datapack reference or a command argument.
     */
    public static IncidentDefinition getOrUnknown(ResourceLocation id) {
        return get(id).orElseGet(() -> IncidentDefinition.unknown(id));
    }

    public static boolean contains(ResourceLocation id) {
        return id != null && definitions.containsKey(id);
    }

    public static Set<ResourceLocation> ids() {
        return definitions.keySet();
    }

    public static Map<ResourceLocation, IncidentDefinition> all() {
        return definitions;
    }

    public static int size() {
        return definitions.size();
    }
}
