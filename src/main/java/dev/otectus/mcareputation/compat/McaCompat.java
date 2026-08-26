package dev.otectus.mcareputation.compat;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import net.minecraft.core.BlockPos;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single point of contact with Minecraft Comes Alive: Reborn (spec §11, §12.4).
 *
 * <p>This class names no MCA type. MCA moves its base package between releases — {@code net.mca}
 * became {@code net.conczin.mca} in 7.7.1 — so every MCA class and method is resolved by name at
 * runtime in {@link McaReflect}, and this class is the policy layer on top: guards, safe defaults,
 * and the vanilla calls that must stay vanilla. See {@link McaReflect} for why reflection is the
 * mechanism and why vanilla methods are deliberately <em>not</em> reflected.
 *
 * <p><b>Every method fails safe, and this time the guard is in the right place.</b> Version 0.2.0
 * claimed the same thing but put its {@code instanceof} <em>outside</em> the {@code try}: class
 * resolution happens at that instruction, so a renamed MCA package threw
 * {@code NoClassDefFoundError} straight through {@code LivingHurtEvent} and killed the server tick
 * loop. Here the type test lives inside the guarded region, and {@link LinkageError} is caught
 * <b>before</b> {@link Throwable} in every method — that ordering is the whole point.
 *
 * <p>A {@code LinkageError} means the ABI assumption is wrong, so it trips a one-shot latch that
 * disables MCA integration for the session and logs one ERROR. Retrying per call would re-throw once
 * per damage tick, which is the log-flood this class was already written to avoid. Ordinary
 * per-call failures keep the old behaviour: a {@code debugLogging}-gated DEBUG line and the
 * documented default.
 */
public final class McaCompat {

    /** Tripped by the first {@link LinkageError}; never reset. See the class javadoc. */
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private McaCompat() {
    }

    private static void fail(String what, Throwable t) {
        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] MCA {} failed; using the safe default", what, t);
        }
    }

    /** Exactly one ERROR per JVM however many threads race here. */
    private static void linkageFailure(String what, LinkageError error) {
        if (DISABLED.compareAndSet(false, true)) {
            McaReputation.LOGGER.error("[MCA: Reputation] MCA integration DISABLED for this session: "
                    + "{} failed to link against the installed MCA. Detected MCA: {}, package root {}. "
                    + "This build supports {}. Update MCA: Reputation, or roll MCA back to a supported "
                    + "version. No new deeds will be recorded; standing already in the save is "
                    + "untouched and still readable with /mcareputation.",
                    what, McaReflect.installedMca(), McaReflect.root(),
                    McaReflect.SUPPORTED_ROOTS, error);
        }
    }

    /** False once MCA integration has been switched off, or when no supported MCA was found. */
    private static boolean live() {
        return !DISABLED.get() && McaReflect.isAvailable();
    }

    // ------------------------------------------------------------------
    // Villager identity
    // ------------------------------------------------------------------

    /** True for an MCA human villager. Zombie variants are a different class and are excluded (§20.1). */
    public static boolean isMcaVillager(Entity entity) {
        if (entity == null || !live()) {
            return false;
        }
        try {
            return McaReflect.isVillager(entity);
        } catch (LinkageError e) {
            linkageFailure("isMcaVillager", e);
            return false;
        } catch (Throwable t) {
            fail("isMcaVillager", t);
            return false;
        }
    }

    /** True for a living MCA villager — the only thing that can witness or be a victim. */
    public static boolean isLivingMcaVillager(Entity entity) {
        if (entity == null || !live()) {
            return false;
        }
        try {
            // isAlive() on a vanilla receiver: reobfJar rewrites the call site, so it must not be
            // reflected. VillagerEntityMCA extends Villager, so the dispatch is the same one 0.2.0 made.
            return McaReflect.isVillager(entity) && entity.isAlive();
        } catch (LinkageError e) {
            linkageFailure("isLivingMcaVillager", e);
            return false;
        } catch (Throwable t) {
            fail("isLivingMcaVillager", t);
            return false;
        }
    }

    /** The villager's display name, for caching onto a subject. Safe default: empty. */
    public static Optional<String> villagerName(Entity entity) {
        if (entity == null || !live()) {
            return Optional.empty();
        }
        try {
            if (!McaReflect.isVillager(entity)) {
                return Optional.empty();
            }
            // Vanilla call on a vanilla receiver, for the same reason as isAlive() above.
            return Optional.ofNullable(entity.getDisplayName()).map(name -> name.getString());
        } catch (LinkageError e) {
            linkageFailure("villagerName", e);
            return Optional.empty();
        } catch (Throwable t) {
            fail("villagerName", t);
            return Optional.empty();
        }
    }

    /** Resolves a possibly-unloaded villager's name from MCA's family tree. Safe default: empty. */
    public static Optional<String> familyTreeName(ServerLevel level, UUID villagerUuid) {
        if (!live()) {
            return Optional.empty();
        }
        try {
            return McaReflect.familyTreeName(level, villagerUuid).filter(name -> !name.isBlank());
        } catch (LinkageError e) {
            linkageFailure("familyTreeName", e);
            return Optional.empty();
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
        if (villager == null || !live()) {
            return Optional.empty();
        }
        try {
            return McaReflect.ageStateName(villager).map(name -> name.toLowerCase(Locale.ROOT));
        } catch (LinkageError e) {
            linkageFailure("ageGroup", e);
            return Optional.empty();
        } catch (Throwable t) {
            fail("ageGroup", t);
            return Optional.empty();
        }
    }

    /** True when the villager is an adult. Fails <b>closed</b>: an MCA read failure means "not adult". */
    public static boolean isAdult(Entity villager) {
        return ageGroup(villager).filter("adult"::equals).isPresent();
    }

    /**
     * The villager's personality as a bare lowercase id ({@code odd}, {@code upbeat}). Safe default:
     * empty.
     *
     * <p>Version-agnostic on purpose: MCA 7.6 declares {@code Personality} as an enum and 7.7 as a
     * registry-backed class, so neither {@code name()} (gone in 7.7) nor {@code getPersonalityId()}
     * (absent in 7.6) can be relied on. {@code toString()} exists in both — {@code "ODD"} on 7.6,
     * {@code "mca:odd"} on 7.7 — and normalising strips the difference.
     */
    public static Optional<String> personality(Entity villager) {
        if (villager == null || !live()) {
            return Optional.empty();
        }
        try {
            return McaReflect.personalityString(villager)
                    .map(McaCompat::normalizePersonality)
                    .filter(id -> !id.isEmpty());
        } catch (LinkageError e) {
            linkageFailure("personality", e);
            return Optional.empty();
        } catch (Throwable t) {
            fail("personality", t);
            return Optional.empty();
        }
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
        if (villager == null || !live()) {
            return OptionalInt.empty();
        }
        try {
            return McaReflect.homeVillageId(villager);
        } catch (LinkageError e) {
            linkageFailure("homeVillageId", e);
            return OptionalInt.empty();
        } catch (Throwable t) {
            fail("homeVillageId", t);
            return OptionalInt.empty();
        }
    }

    /** The nearest village id within {@code radius} of a position. Safe default: empty. */
    public static OptionalInt nearestVillageId(ServerLevel level, BlockPos pos, int radius) {
        if (!live()) {
            return OptionalInt.empty();
        }
        try {
            return McaReflect.nearestVillageId(level, pos, radius);
        } catch (LinkageError e) {
            linkageFailure("nearestVillageId", e);
            return OptionalInt.empty();
        } catch (Throwable t) {
            fail("nearestVillageId", t);
            return OptionalInt.empty();
        }
    }

    /** True when a village with this id currently exists in this level. Safe default: false. */
    public static boolean villageExists(ServerLevel level, int villageId) {
        if (!live()) {
            return false;
        }
        try {
            return McaReflect.villageExists(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("villageExists", e);
            return false;
        } catch (Throwable t) {
            fail("villageExists", t);
            return false;
        }
    }

    /** The village's current name. Safe default: empty (the caller falls back to its cached copy). */
    public static Optional<String> villageName(ServerLevel level, int villageId) {
        if (!live()) {
            return Optional.empty();
        }
        try {
            return McaReflect.villageName(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("villageName", e);
            return Optional.empty();
        } catch (Throwable t) {
            fail("villageName", t);
            return Optional.empty();
        }
    }

    /** The village's centre anchor. Safe default: empty. */
    public static Optional<BlockPos> villageCenter(ServerLevel level, int villageId) {
        if (!live()) {
            return Optional.empty();
        }
        try {
            return McaReflect.villageCenter(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("villageCenter", e);
            return Optional.empty();
        } catch (Throwable t) {
            fail("villageCenter", t);
            return Optional.empty();
        }
    }

    /** True when a position lies inside the village's border. Safe default: false. */
    public static boolean isWithinVillage(ServerLevel level, int villageId, BlockPos pos) {
        if (!live()) {
            return false;
        }
        try {
            return McaReflect.isWithinVillage(level, villageId, pos);
        } catch (LinkageError e) {
            linkageFailure("isWithinVillage", e);
            return false;
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
        if (!live()) {
            return new HashSet<>();
        }
        try {
            return McaReflect.residentUuids(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("residentUuids", e);
            return new HashSet<>();
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
        if (!live()) {
            return new ArrayList<>();
        }
        try {
            return McaReflect.loadedResidents(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("loadedResidents", e);
            return new ArrayList<>();
        } catch (Throwable t) {
            fail("loadedResidents", t);
            return new ArrayList<>();
        }
    }

    /** UUID → name for the full residency set, including unloaded residents. Safe default: empty map. */
    public static Map<UUID, String> residentNames(ServerLevel level, int villageId) {
        if (!live()) {
            return new HashMap<>();
        }
        try {
            return McaReflect.residentNames(level, villageId);
        } catch (LinkageError e) {
            linkageFailure("residentNames", e);
            return new HashMap<>();
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
     * <p>Fails <b>open</b> (returns true) on a vanilla read failure, unlike most of this class.
     * The asymmetry is deliberate: the alternative default would mean a broken line-of-sight check
     * silently makes every crime unwitnessed, which is both the more exploitable outcome and the
     * harder one to notice. Witnessing something that was in fact behind a wall is a cosmetic
     * inaccuracy; letting murder go unnoticed is not.
     *
     * <p>Touches no MCA type, so it sits deliberately <b>outside</b> the disable latch: switching it
     * off when MCA fails to link would silently un-witness every deed for the rest of the session.
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
