package dev.otectus.mcareputation.compat;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Resolves Minecraft Comes Alive by name at runtime, because MCA moves its base package.
 *
 * <p><b>Why reflection and not imports.</b> MCA Reborn ships a Forgix-merged "Universal" jar that
 * relocates each loader's classes under a loader-named root, and MCA renamed its base package from
 * {@code net.mca} to {@code net.conczin.mca} in {@code 7.7.1-alpha.2}. The Forge root therefore moved
 * from {@code forge.net.mca.*} to {@code forge.net.conczin.mca.*}, with no overlap — 7.7.1 ships zero
 * {@code forge/net/mca/} entries. MCA's mod id is still {@code mca}, so the {@code [7.6,8)} range in
 * mods.toml accepts both, and a compile-time import against either root is a server-killer against
 * the other: 0.2.0 died with {@code NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA} on
 * the first {@code LivingHurtEvent}. Every signature this mod consumes is byte-identical across the
 * two — verified with {@code javap} — so only the names need to be late-bound.
 *
 * <p><b>Class names appear as string literals only.</b> Never a class literal, never a method
 * reference to an MCA type. Either would put a {@code CONSTANT_Class} in this class's constant pool
 * and reintroduce exactly the linkage failure this exists to prevent.
 *
 * <p><b>Vanilla methods are never reflected.</b> {@code reobfJar} rewrites vanilla call sites to SRG
 * names ({@code getDisplayName} becomes {@code m_5446_}) but does not rewrite string literals, so
 * reflecting a vanilla method would need a hardcoded SRG name that is wrong in dev and brittle in
 * production. {@code VillagerEntityMCA} extends {@code net.minecraft.world.entity.npc.Villager}, so
 * callers invoke {@code getDisplayName()} and {@code isAlive()} on a statically-typed {@link Entity}
 * receiver instead and let the remapper do its job. Only MCA's own methods — which survive
 * obfuscation unrenamed — are named here as strings.
 *
 * <p>Resolution happens once, in a static initialiser that <b>cannot throw</b>: on any failure the
 * fields are left null, {@link #isAvailable()} reports false, and every accessor returns its safe
 * default. {@link #selfTest()} runs at common setup so an incompatibility is one ERROR line at
 * startup rather than a surprise mid-tick.
 */
public final class McaReflect {

    /**
     * Package roots to probe, newest MCA first. The bare roots carry no Forgix loader prefix; MCA
     * builds for 1.21 are packaged that way, so a differently-packaged MCA is recognised rather than
     * mistaken for a missing dependency.
     */
    static final List<String> SUPPORTED_ROOTS = List.of(
            "forge.net.conczin.mca",  // MCA 7.7.1+
            "forge.net.mca",          // MCA 7.6 - 7.7.0
            "net.conczin.mca",
            "net.mca");

    private static final String ROOT;
    private static final List<String> MISSING;
    private static final boolean AVAILABLE;

    /** Hot path: consulted once per damage event and once per death. */
    private static final Class<?> VILLAGER;
    private static final Class<?> VILLAGER_LIKE;

    private static final MethodHandle GET_AGE_STATE;
    private static final MethodHandle GET_VILLAGER_BRAIN;
    private static final MethodHandle GET_PERSONALITY;
    private static final MethodHandle GET_RESIDENCY;
    private static final MethodHandle GET_HOME_VILLAGE;
    private static final MethodHandle VILLAGE_GET_ID;
    private static final MethodHandle VILLAGE_GET_NAME;
    private static final MethodHandle VILLAGE_GET_CENTER;
    private static final MethodHandle VILLAGE_IS_WITHIN_BORDER;
    private static final MethodHandle VILLAGE_GET_RESIDENTS;
    private static final MethodHandle VILLAGE_GET_RESIDENT_UUIDS;
    private static final MethodHandle VILLAGE_GET_RESIDENT_NAMES;
    private static final MethodHandle MANAGER_GET;
    private static final MethodHandle MANAGER_GET_OR_EMPTY;
    private static final MethodHandle MANAGER_FIND_NEAREST;
    private static final MethodHandle FAMILY_TREE_GET;
    private static final MethodHandle FAMILY_TREE_GET_OR_EMPTY;
    private static final MethodHandle NODE_GET_NAME;

    static {
        String root = null;
        Class<?> villager = null;
        Class<?> villagerLike = null;
        Class<?> villagerBrain = null;
        Class<?> residency = null;
        Class<?> village = null;
        Class<?> villageManager = null;
        Class<?> familyTree = null;
        Class<?> familyTreeNode = null;
        MethodHandle getAgeState = null;
        MethodHandle getVillagerBrain = null;
        MethodHandle getPersonality = null;
        MethodHandle getResidency = null;
        MethodHandle getHomeVillage = null;
        MethodHandle villageGetId = null;
        MethodHandle villageGetName = null;
        MethodHandle villageGetCenter = null;
        MethodHandle villageIsWithinBorder = null;
        MethodHandle villageGetResidents = null;
        MethodHandle villageGetResidentUuids = null;
        MethodHandle villageGetResidentNames = null;
        MethodHandle managerGet = null;
        MethodHandle managerGetOrEmpty = null;
        MethodHandle managerFindNearest = null;
        MethodHandle familyTreeGet = null;
        MethodHandle familyTreeGetOrEmpty = null;
        MethodHandle nodeGetName = null;
        List<String> missing = new ArrayList<>();

        try {
            root = detectRoot();
            if (root != null) {
                villager = type(missing, root, "entity.VillagerEntityMCA");
                villagerLike = type(missing, root, "entity.VillagerLike");
                villagerBrain = type(missing, root, "entity.ai.brain.VillagerBrain");
                residency = type(missing, root, "entity.ai.Residency");
                village = type(missing, root, "server.world.data.Village");
                villageManager = type(missing, root, "server.world.data.VillageManager");
                familyTree = type(missing, root, "server.world.data.FamilyTree");
                familyTreeNode = type(missing, root, "server.world.data.FamilyTreeNode");

                getAgeState = method(missing, villagerLike, "getAgeState");
                getVillagerBrain = method(missing, villager, "getVillagerBrain");
                getPersonality = method(missing, villagerBrain, "getPersonality");
                getResidency = method(missing, villager, "getResidency");
                getHomeVillage = method(missing, residency, "getHomeVillage");

                villageGetId = method(missing, village, "getId");
                villageGetName = method(missing, village, "getName");
                villageGetCenter = method(missing, village, "getCenter");
                villageIsWithinBorder = method(missing, village, "isWithinBorder", BlockPos.class, int.class);
                // Overloaded: getResidents(int) yields names, getResidents(ServerLevel) yields entities.
                villageGetResidents = method(missing, village, "getResidents", ServerLevel.class);
                villageGetResidentUuids = method(missing, village, "getResidentsUUIDs");
                villageGetResidentNames = method(missing, village, "getResidentNames");

                managerGet = method(missing, villageManager, "get", ServerLevel.class);
                managerGetOrEmpty = method(missing, villageManager, "getOrEmpty", int.class);
                managerFindNearest = method(missing, villageManager, "findNearestVillage",
                        BlockPos.class, int.class);

                familyTreeGet = method(missing, familyTree, "get", ServerLevel.class);
                familyTreeGetOrEmpty = method(missing, familyTree, "getOrEmpty", UUID.class);
                nodeGetName = method(missing, familyTreeNode, "getName");
            }
        } catch (Throwable t) {
            // A static initialiser that throws would turn every later access into
            // ExceptionInInitializerError: the crash this class exists to prevent, in a new costume.
            missing.add("bridge initialisation threw " + t);
        }

        ROOT = root;
        VILLAGER = villager;
        VILLAGER_LIKE = villagerLike;
        GET_AGE_STATE = getAgeState;
        GET_VILLAGER_BRAIN = getVillagerBrain;
        GET_PERSONALITY = getPersonality;
        GET_RESIDENCY = getResidency;
        GET_HOME_VILLAGE = getHomeVillage;
        VILLAGE_GET_ID = villageGetId;
        VILLAGE_GET_NAME = villageGetName;
        VILLAGE_GET_CENTER = villageGetCenter;
        VILLAGE_IS_WITHIN_BORDER = villageIsWithinBorder;
        VILLAGE_GET_RESIDENTS = villageGetResidents;
        VILLAGE_GET_RESIDENT_UUIDS = villageGetResidentUuids;
        VILLAGE_GET_RESIDENT_NAMES = villageGetResidentNames;
        MANAGER_GET = managerGet;
        MANAGER_GET_OR_EMPTY = managerGetOrEmpty;
        MANAGER_FIND_NEAREST = managerFindNearest;
        FAMILY_TREE_GET = familyTreeGet;
        FAMILY_TREE_GET_OR_EMPTY = familyTreeGetOrEmpty;
        NODE_GET_NAME = nodeGetName;
        MISSING = List.copyOf(missing);
        AVAILABLE = root != null && missing.isEmpty();
    }

    private McaReflect() {
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** The first root whose two sentinel classes both resolve, or null when MCA is absent/unknown. */
    private static String detectRoot() {
        for (String candidate : SUPPORTED_ROOTS) {
            if (resolves(candidate + ".entity.VillagerEntityMCA")
                    && resolves(candidate + ".server.world.data.VillageManager")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code initialize = false}: resolution is all that is wanted here, not {@code <clinit>}. The
     * JVM still loads supertypes while defining the class, so a class whose hierarchy is broken fails
     * here rather than later, which is the point. Catching {@link Throwable} rather than
     * {@link ClassNotFoundException} is therefore load-bearing, not padding.
     */
    private static boolean resolves(String name) {
        try {
            Class.forName(name, false, McaReflect.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> type(List<String> missing, String root, String suffix) {
        String name = root + '.' + suffix;
        try {
            return Class.forName(name, false, McaReflect.class.getClassLoader());
        } catch (Throwable t) {
            missing.add("class " + name);
            return null;
        }
    }

    /**
     * {@code getMethod} rather than a hand-written {@code MethodType}, because several of these
     * signatures are generic and writing their erasures out by hand is a silent-breakage risk.
     */
    private static MethodHandle method(List<String> missing, Class<?> owner, String name,
                                       Class<?>... params) {
        if (owner == null) {
            return null; // its class already recorded a miss; do not report the same failure twice
        }
        try {
            Method resolved = owner.getMethod(name, params);
            return MethodHandles.lookup().unreflect(resolved);
        } catch (Throwable t) {
            missing.add(owner.getName() + '#' + name);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** True when a supported MCA was found and every member this mod consumes resolved. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** The detected package root, for logging. Never null in a message. */
    public static String root() {
        return ROOT == null ? "none" : ROOT;
    }

    /** The MCA build actually installed, for logging. */
    public static String installedMca() {
        try {
            return ModList.get().getModContainerById(McaReputation.MCA_MOD_ID)
                    .map(container -> container.getModInfo().getModId() + " "
                            + container.getModInfo().getVersion())
                    .orElse("not installed");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Forces resolution before any gameplay and reports the outcome in exactly one line.
     *
     * <p>This is what a compile-time binding cannot give: the check runs against the MCA that is
     * actually installed, not the one this jar happened to be built against.
     */
    public static void selfTest() {
        if (AVAILABLE) {
            McaReputation.LOGGER.info("[MCA: Reputation] MCA integration active: {} (package root {})",
                    installedMca(), root());
            return;
        }
        if (ROOT == null) {
            McaReputation.LOGGER.error("[MCA: Reputation] No supported MCA package root found. "
                    + "Detected MCA: {}. This build supports {}. No new deeds will be recorded; "
                    + "standing already in the save is untouched and still readable with "
                    + "/mcareputation.", installedMca(), SUPPORTED_ROOTS);
            return;
        }
        McaReputation.LOGGER.error("[MCA: Reputation] MCA integration DISABLED: found package root {} "
                + "for {}, but these members did not resolve: {}. MCA has changed a signature this "
                + "mod depends on. No new deeds will be recorded; standing already in the save is "
                + "untouched and still readable with /mcareputation.", root(), installedMca(), MISSING);
    }

    // ------------------------------------------------------------------
    // Villager identity - the hot path
    // ------------------------------------------------------------------

    /** True for an MCA human villager. Zombie variants are a separate class and do not match. */
    public static boolean isVillager(Entity entity) {
        return VILLAGER != null && VILLAGER.isInstance(entity);
    }

    /** True for anything MCA treats as villager-like, human or zombie. */
    public static boolean isVillagerLike(Entity entity) {
        return VILLAGER_LIKE != null && VILLAGER_LIKE.isInstance(entity);
    }

    // ------------------------------------------------------------------
    // Villager reads
    // ------------------------------------------------------------------

    /**
     * The raw MCA age state name. {@code AgeState} is an enum on every MCA seen so far;
     * {@code toString()} is the fallback in case a future release makes it a registry class, the same
     * drift {@code Personality} already went through.
     */
    public static Optional<String> ageStateName(Entity villager) throws Throwable {
        if (!AVAILABLE || !isVillagerLike(villager)) {
            return Optional.empty();
        }
        Object state = GET_AGE_STATE.invoke(villager);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(state instanceof Enum<?> constant ? constant.name() : state.toString());
    }

    /** The raw personality {@code toString()}: {@code "ODD"} on 7.6, {@code "mca:odd"} on 7.7. */
    public static Optional<String> personalityString(Entity villager) throws Throwable {
        if (!AVAILABLE || !isVillager(villager)) {
            return Optional.empty();
        }
        Object brain = GET_VILLAGER_BRAIN.invoke(villager);
        if (brain == null) {
            return Optional.empty();
        }
        Object personality = GET_PERSONALITY.invoke(brain);
        return personality == null ? Optional.empty() : Optional.of(personality.toString());
    }

    /** The id of the villager's home village. */
    public static OptionalInt homeVillageId(Entity villager) throws Throwable {
        if (!AVAILABLE || !isVillager(villager)) {
            return OptionalInt.empty();
        }
        Object residency = GET_RESIDENCY.invoke(villager);
        if (residency == null) {
            return OptionalInt.empty();
        }
        Optional<?> home = asOptional(GET_HOME_VILLAGE.invoke(residency));
        return home.isEmpty() ? OptionalInt.empty() : OptionalInt.of(villageId(home.get()));
    }

    // ------------------------------------------------------------------
    // Family tree
    // ------------------------------------------------------------------

    /** A possibly-unloaded villager's recorded name. */
    public static Optional<String> familyTreeName(ServerLevel level, UUID villagerUuid) throws Throwable {
        if (!AVAILABLE || level == null || villagerUuid == null) {
            return Optional.empty();
        }
        Object tree = FAMILY_TREE_GET.invoke(level);
        if (tree == null) {
            return Optional.empty();
        }
        Optional<?> node = asOptional(FAMILY_TREE_GET_OR_EMPTY.invoke(tree, villagerUuid));
        if (node.isEmpty()) {
            return Optional.empty();
        }
        Object name = NODE_GET_NAME.invoke(node.get());
        return name == null ? Optional.empty() : Optional.of(name.toString());
    }

    // ------------------------------------------------------------------
    // Villages
    // ------------------------------------------------------------------

    /** The MCA village object for this id, or empty. The internal currency for the reads below. */
    private static Optional<?> village(ServerLevel level, int villageId) throws Throwable {
        if (!AVAILABLE || level == null) {
            return Optional.empty();
        }
        Object manager = MANAGER_GET.invoke(level);
        return manager == null ? Optional.empty()
                : asOptional(MANAGER_GET_OR_EMPTY.invoke(manager, villageId));
    }

    private static int villageId(Object village) throws Throwable {
        return (int) VILLAGE_GET_ID.invoke(village);
    }

    /** The nearest village id within {@code radius} of a position. */
    public static OptionalInt nearestVillageId(ServerLevel level, BlockPos pos, int radius) throws Throwable {
        if (!AVAILABLE || level == null || pos == null) {
            return OptionalInt.empty();
        }
        Object manager = MANAGER_GET.invoke(level);
        if (manager == null) {
            return OptionalInt.empty();
        }
        Optional<?> nearest = asOptional(MANAGER_FIND_NEAREST.invoke(manager, pos, radius));
        return nearest.isEmpty() ? OptionalInt.empty() : OptionalInt.of(villageId(nearest.get()));
    }

    public static boolean villageExists(ServerLevel level, int villageId) throws Throwable {
        return village(level, villageId).isPresent();
    }

    public static Optional<String> villageName(ServerLevel level, int villageId) throws Throwable {
        Optional<?> village = village(level, villageId);
        if (village.isEmpty()) {
            return Optional.empty();
        }
        Object name = VILLAGE_GET_NAME.invoke(village.get());
        return name == null ? Optional.empty() : Optional.of(name.toString());
    }

    public static Optional<BlockPos> villageCenter(ServerLevel level, int villageId) throws Throwable {
        Optional<?> village = village(level, villageId);
        if (village.isEmpty()) {
            return Optional.empty();
        }
        Vec3i center = (Vec3i) VILLAGE_GET_CENTER.invoke(village.get());
        return center == null ? Optional.empty()
                : Optional.of(new BlockPos(center.getX(), center.getY(), center.getZ()));
    }

    public static boolean isWithinVillage(ServerLevel level, int villageId, BlockPos pos) throws Throwable {
        Optional<?> village = village(level, villageId);
        return village.isPresent() && pos != null
                && (boolean) VILLAGE_IS_WITHIN_BORDER.invoke(village.get(), pos, 0);
    }

    /** The full resident UUID set, independent of chunk loading. */
    public static Set<UUID> residentUuids(ServerLevel level, int villageId) throws Throwable {
        Optional<?> village = village(level, villageId);
        Set<UUID> uuids = new HashSet<>();
        if (village.isEmpty()) {
            return uuids;
        }
        Object raw = VILLAGE_GET_RESIDENT_UUIDS.invoke(village.get());
        if (raw instanceof Stream<?> stream) {
            stream.forEach(value -> {
                if (value instanceof UUID uuid) {
                    uuids.add(uuid);
                }
            });
        }
        return uuids;
    }

    /** Currently-loaded resident entities. */
    public static List<Entity> loadedResidents(ServerLevel level, int villageId) throws Throwable {
        Optional<?> village = village(level, villageId);
        List<Entity> residents = new ArrayList<>();
        if (village.isEmpty()) {
            return residents;
        }
        Object raw = VILLAGE_GET_RESIDENTS.invoke(village.get(), level);
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Entity entity) {
                    residents.add(entity);
                }
            }
        }
        return residents;
    }

    /** UUID to name for the full residency set, including unloaded residents. */
    public static Map<UUID, String> residentNames(ServerLevel level, int villageId) throws Throwable {
        Optional<?> village = village(level, villageId);
        Map<UUID, String> names = new HashMap<>();
        if (village.isEmpty()) {
            return names;
        }
        Object raw = VILLAGE_GET_RESIDENT_NAMES.invoke(village.get());
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key instanceof UUID uuid && value != null) {
                    names.put(uuid, value.toString());
                }
            });
        }
        return names;
    }

    private static Optional<?> asOptional(Object value) {
        return value instanceof Optional<?> optional ? optional : Optional.empty();
    }
}
