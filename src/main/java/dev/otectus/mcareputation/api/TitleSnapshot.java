package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every title a player currently holds, in one object (§6 "Title synchronization").
 *
 * <p>The whole set, not a delta: a grants-only stream cannot repair a fallback copy that has drifted,
 * and a mirror that missed a revocation has no way to discover it from grants alone. This is what
 * {@link ReputationMirror#mirrorTitleState} carries, and it is authoritative as of the revision it
 * arrives with.
 *
 * @since MCA: Reputation 0.4.1
 */
public record TitleSnapshot(Set<ResourceLocation> globalTitles,
                            Map<CommunityKey, Set<ResourceLocation>> villageTitles) {

    public static final TitleSnapshot EMPTY = new TitleSnapshot(Set.of(), Map.of());

    public TitleSnapshot {
        globalTitles = globalTitles == null ? Set.of() : Set.copyOf(globalTitles);
        if (villageTitles == null) {
            villageTitles = Map.of();
        } else {
            Map<CommunityKey, Set<ResourceLocation>> copy = new LinkedHashMap<>();
            villageTitles.forEach((key, titles) -> copy.put(key,
                    titles == null ? Set.of() : Set.copyOf(titles)));
            villageTitles = Map.copyOf(copy);
        }
    }

    /** Whether this snapshot holds the title anywhere: globally, or in any community. */
    public boolean has(ResourceLocation title) {
        return globalTitles.contains(title)
                || villageTitles.values().stream().anyMatch(titles -> titles.contains(title));
    }

    public boolean isEmpty() {
        return globalTitles.isEmpty() && villageTitles.values().stream().allMatch(Set::isEmpty);
    }
}
