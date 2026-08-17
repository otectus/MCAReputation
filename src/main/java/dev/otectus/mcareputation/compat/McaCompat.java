package dev.otectus.mcareputation.compat;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import forge.net.mca.entity.VillagerEntityMCA;
import forge.net.mca.entity.VillagerLike;
import forge.net.mca.entity.ai.relationship.AgeState;
import forge.net.mca.server.world.data.FamilyTree;
import forge.net.mca.server.world.data.FamilyTreeNode;
import forge.net.mca.server.world.data.Village;
import forge.net.mca.server.world.data.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * The single point of contact with Minecraft Comes Alive: Reborn (spec §11, §12.4).
 *
 * <p><b>Why {@code forge.net.mca.*} and not {@code net.mca.*}?</b> MCA Reborn ships a Forgix-merged
 * "Universal" jar covering Forge, Fabric, and Quilt. Forgix relocates each loader's classes under a
 * loader-named root package, so the Forge classes are physically {@code forge.net.mca.*} in both the
 * production jar and the dev-remapped one. The sibling MCA add-ons take the same approach.
 *
 * <p><b>Every method fails safe.</b> {@code instanceof} guards, {@code try/catch (Throwable)}, a DEBUG
 * log, and a documented default. MCA publishes no stable API, so a signature that moves between 7.6
 * and 7.7 — or in a future release — must degrade this mod's behaviour, never crash a server mid-tick.
 * The DEBUG logs are gated behind {@code debugLogging} so a persistent incompatibility does not fill
 * the log at one line per event.
 *
 * <p>Phase 0 verified with {@code javap} that every signature consumed here is byte-identical on MCA
 * {@code 7.6.20} and {@code 7.7.0-beta.2}, so no reflection or version branch is needed. The one known
 * drift — {@code Personality} moving from an enum to a registry class — is consumed only through
 * {@code Object#toString()}, which exists on both.
 */
public final class McaCompat {

    private McaCompat() {
    }

    private static void fail(String what, Throwable t) {
        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] MCA {} failed; using the safe default", what, t);
        }
    }

    // ------------------------------------------------------------------
    // Villager identity
    // ------------------------------------------------------------------

    /** True for an MCA human villager. Zombie variants are a different class and are excluded (§20.1). */
    public static boolean isMcaVillager(Entity entity) {
        return entity instanceof VillagerEntityMCA;
    }

    /** True for a living MCA villager — the only thing that can witness or be a victim. */
    public static boolean isLivingMcaVillager(Entity entity) {
        return entity instanceof VillagerEntityMCA villager && villager.isAlive();
    }

    /** The villager's display name, for caching onto a subject. Safe default: empty. */
    public static Optional<String> villagerName(Entity entity) {
        if (entity instanceof VillagerEntityMCA villager) {
            try {
                return Optional.ofNullable(villager.getDisplayName()).map(name -> name.getString());
            } catch (Throwable t) {
                fail("villagerName", t);
            }
        }
        return Optional.empty();
    }

    /** Resolves a possibly-unloaded villager's name from MCA's family tree. Safe default: empty. */
    public static Optional<String> familyTreeName(ServerLevel level, UUID villagerUuid) {
        try {
            return FamilyTree.get(level).getOrEmpty(villagerUuid)
                    .map(FamilyTreeNode::getName)
                    .filter(name -> !name.isBlank());
        } catch (Throwable t) {
            fail("familyTreeName", t);
            return Optional.empty();
        }
    }

    /**
     * The villager's MCA age state as a lowercase name ({@code baby}, {@code toddler}, {@code child},
     * {@code teen}, {@code adult}). Safe default: empty.
     *
     * <p>Used by the Conversations integration so babies and toddlers do not deliver civic assessments
     * of the player's standing (§30.5).
     */
    public static Optional<String> ageGroup(Entity villager) {
        if (villager instanceof VillagerLike<?> like) {
            try {
                AgeState state = like.getAgeState();
                return state == null ? Optional.empty() : Optional.of(state.name().toLowerCase(Locale.ROOT));
            } catch (Throwable t) {
                fail("ageGroup", t);
            }
        }
        return Optional.empty();
    }

    /** True when the villager is an adult. Fails <b>closed</b>: an MCA read failure means "not adult". */
    public static boolean isAdult(Entity villager) {
        if (villager instanceof VillagerLike<?> like) {
            try {
                return like.getAgeState() == AgeState.ADULT;
            } catch (Throwable t) {
                fail("isAdult", t);
            }
        }
        return false;
    }

    /**
     * The villager's personality as a bare lowercase id ({@code odd}, {@code upbeat}). Safe default:
     * empty.
     *
     * <p>Version-agnostic on purpose: MCA 7.6 declares {@code Personality} as an enum and 7.7 as a
     * registry-backed class, so neither {@code name()} (gone in 7.7) nor {@code getPersonalityId()}
     * (absent in 7.6) can be called from one binary without reflection. {@code toString()} exists in
     * both — {@code "ODD"} on 7.6, {@code "mca:odd"} on 7.7 — and normalising strips the difference.
     */
    public static Optional<String> personality(Entity villager) {
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                return Optional.ofNullable(mca.getVillagerBrain().getPersonality())
                        .map(Object::toString)
                        .map(McaCompat::normalizePersonality)
                        .filter(id -> !id.isEmpty());
            } catch (Throwable t) {
                fail("personality", t);
            }
        }
        return Optional.empty();
    }

    private static String normalizePersonality(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    // ------------------------------------------------------------------
    // Villages
    // ------------------------------------------------------------------

    /** The id of the villager's home village. Safe default: empty. */
    public static OptionalInt homeVillageId(Entity villager) {
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                return mca.getResidency().getHomeVillage()
                        .map(village -> OptionalInt.of(village.getId()))
                        .orElseGet(OptionalInt::empty);
            } catch (Throwable t) {
                fail("homeVillageId", t);
            }
        }
        return OptionalInt.empty();
    }

    /** The nearest village id within {@code radius} of a position. Safe default: empty. */
    public static OptionalInt nearestVillageId(ServerLevel level, BlockPos pos, int radius) {
        try {
            return VillageManager.get(level).findNearestVillage(pos, radius)
                    .map(village -> OptionalInt.of(village.getId()))
                    .orElseGet(OptionalInt::empty);
        } catch (Throwable t) {
            fail("nearestVillageId", t);
            return OptionalInt.empty();
        }
    }

    /** True when a village with this id currently exists in this level. Safe default: false. */
    public static boolean villageExists(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId).isPresent();
        } catch (Throwable t) {
            fail("villageExists", t);
            return false;
        }
    }

    /** The village's current name. Safe default: empty (the caller falls back to its cached copy). */
    public static Optional<String> villageName(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId).map(Village::getName);
        } catch (Throwable t) {
            fail("villageName", t);
            return Optional.empty();
        }
    }

    /** The village's centre anchor. Safe default: empty. */
    public static Optional<BlockPos> villageCenter(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId).map(village -> {
                Vec3i center = village.getCenter();
                return new BlockPos(center.getX(), center.getY(), center.getZ());
            });
        } catch (Throwable t) {
            fail("villageCenter", t);
            return Optional.empty();
        }
    }

    /** True when a position lies inside the village's border. Safe default: false. */
    public static boolean isWithinVillage(ServerLevel level, int villageId, BlockPos pos) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId)
                    .map(village -> village.isWithinBorder(pos, 0))
                    .orElse(false);
        } catch (Throwable t) {
            fail("isWithinVillage", t);
            return false;
        }
    }

    /**
     * The village's full resident UUID set, <b>independent of chunk loading</b>, so an unloaded
     * villager is never mistaken for someone who left. This is what residency-based awareness checks
     * must use. Safe default: empty set.
     */
    public static Set<UUID> residentUuids(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId)
                    .map(village -> village.getResidentsUUIDs()
                            .collect(java.util.stream.Collectors.toCollection(HashSet<UUID>::new)))
                    .orElseGet(HashSet::new);
        } catch (Throwable t) {
            fail("residentUuids", t);
            return new HashSet<>();
        }
    }

    /** True when this villager currently belongs to this village. Safe default: false. */
    public static boolean isResident(ServerLevel level, int villageId, UUID villagerUuid) {
        return villagerUuid != null && residentUuids(level, villageId).contains(villagerUuid);
    }

    /** Currently-loaded resident entities. Safe default: empty list. */
    public static List<Entity> loadedResidents(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId)
                    .map(village -> new ArrayList<Entity>(village.getResidents(level)))
                    .orElseGet(ArrayList::new);
        } catch (Throwable t) {
            fail("loadedResidents", t);
            return new ArrayList<>();
        }
    }

    /** UUID → name for the full residency set, including unloaded residents. Safe default: empty map. */
    public static Map<UUID, String> residentNames(ServerLevel level, int villageId) {
        try {
            return VillageManager.get(level).getOrEmpty(villageId)
                    .map(village -> new HashMap<>(village.getResidentNames()))
                    .orElseGet(HashMap::new);
        } catch (Throwable t) {
            fail("residentNames", t);
            return new HashMap<>();
        }
    }

    // ------------------------------------------------------------------
    // Witnessing
    // ------------------------------------------------------------------

    /**
     * Whether {@code observer} can actually see {@code target}.
     *
     * <p>Fails <b>open</b> (returns true) on an MCA/vanilla read failure, unlike most of this class.
     * The asymmetry is deliberate: the alternative default would mean a broken line-of-sight check
     * silently makes every crime unwitnessed, which is both the more exploitable outcome and the
     * harder one to notice. Witnessing something that was in fact behind a wall is a cosmetic
     * inaccuracy; letting murder go unnoticed is not.
     */
    public static boolean hasLineOfSight(LivingEntity observer, Entity target) {
        try {
            return observer.hasLineOfSight(target);
        } catch (Throwable t) {
            fail("hasLineOfSight", t);
            return true;
        }
    }
}
