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
 *
 * <p>The audited surface has <b>two tiers</b>. A member resolved through {@link #method} is required:
 * a miss there switches the whole integration off, because a deed recorded without it would be
 * wrong. A member resolved through {@code optionalMethod} only enriches an answer that already has a
 * mandatory neutral fallback; a miss there is listed in {@link #missingOptional()} and warned about
 * at startup, and nothing else changes. Both tiers are resolved at startup, so neither can surprise
 * a running server.
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

    /** One MCA member this mod consumes: the owning class by suffix under the root, its name, its parameters. */
    record Member(String key, String owner, String name, boolean optional, Class<?>... params) {
    }

    /** The result of replaying the manifest against a class loader; binds nothing. */
    record Resolution(String root, List<String> missing, List<String> missingOptional) {
    }

    /** The MCA classes this mod resolves, by suffix under the detected package root. */
    static final List<String> TYPES = List.of(
            "entity.VillagerEntityMCA",
            "entity.VillagerLike",
            "entity.ai.brain.VillagerBrain",
            "entity.ai.Residency",
            "server.world.data.Village",
            "server.world.data.VillageManager",
            "server.world.data.FamilyTree",
            "server.world.data.FamilyTreeNode");

    /**
     * The audited member manifest: the single table the static initialiser binds from and
     * {@code McaReflectProbeTest} replays against every supported MCA jar. An optional member (see
     * {@link #optionalMethod}) only enriches an answer with a neutral fallback; every other one is
     * load-bearing.
     */
    static final List<Member> MANIFEST = List.of(
            new Member("getAgeState", "entity.VillagerLike", "getAgeState", false),
            new Member("getVillagerBrain", "entity.VillagerEntityMCA", "getVillagerBrain", false),
            new Member("getPersonality", "entity.ai.brain.VillagerBrain", "getPersonality", false),
            // A removed profession getter costs role sensitivity, and must not cost deed recording.
            new Member("getProfessionId", "entity.VillagerEntityMCA", "getProfessionId", true),
            new Member("getResidency", "entity.VillagerEntityMCA", "getResidency", false),
            new Member("getHomeVillage", "entity.ai.Residency", "getHomeVillage", false),
            new Member("village.getId", "server.world.data.Village", "getId", false),
            new Member("village.getName", "server.world.data.Village", "getName", false),
            new Member("village.getCenter", "server.world.data.Village", "getCenter", false),
            new Member("village.isWithinBorder", "server.world.data.Village", "isWithinBorder", false,
                    BlockPos.class, int.class),
            // Overloaded: getResidents(int) yields names, getResidents(ServerLevel) yields entities.
            new Member("village.getResidents", "server.world.data.Village", "getResidents", false,
                    ServerLevel.class),
            new Member("village.getResidentsUUIDs", "server.world.data.Village", "getResidentsUUIDs", false),
            new Member("village.getResidentNames", "server.world.data.Village", "getResidentNames", false),
            new Member("manager.get", "server.world.data.VillageManager", "get", false, ServerLevel.class),
            new Member("manager.getOrEmpty", "server.world.data.VillageManager", "getOrEmpty", false, int.class),
            new Member("manager.findNearestVillage", "server.world.data.VillageManager", "findNearestVillage",
                    false, BlockPos.class, int.class),
            new Member("familyTree.get", "server.world.data.FamilyTree", "get", false, ServerLevel.class),
            new Member("familyTree.getOrEmpty", "server.world.data.FamilyTree", "getOrEmpty", false, UUID.class),
            new Member("node.getName", "server.world.data.FamilyTreeNode", "getName", false));

    private static final String ROOT;
    private static final List<String> MISSING;
    private static final List<String> MISSING_OPTIONAL;
    private static final boolean AVAILABLE;

    /** Hot path: consulted once per damage event and once per death. */
    private static final Class<?> VILLAGER;
    private static final Class<?> VILLAGER_LIKE;

    private static final MethodHandle GET_AGE_STATE;
    private static final MethodHandle GET_VILLAGER_BRAIN;
    private static final MethodHandle GET_PERSONALITY;
    private static final MethodHandle GET_PROFESSION_ID;
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
        Map<String, Class<?>> classes = new HashMap<>();
        Map<String, MethodHandle> handles = new HashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();

        try {
            ClassLoader loader = McaReflect.class.getClassLoader();
            root = detectRoot(loader);
            if (root != null) {
                for (String suffix : TYPES) {
                    classes.put(suffix, type(missing, root, suffix, loader));
                }
                for (Member member : MANIFEST) {
                    Class<?> owner = classes.get(member.owner());
                    handles.put(member.key(), member.optional()
                            ? optionalMethod(missingOptional, owner, member.name(), member.params())
                            : method(missing, owner, member.name(), member.params()));
                }
            }
        } catch (Throwable t) {
            // A static initialiser that throws would turn every later access into
            // ExceptionInInitializerError: the crash this class exists to prevent, in a new costume.
            missing.add("bridge initialisation threw " + t);
        }

        ROOT = root;
        VILLAGER = classes.get("entity.VillagerEntityMCA");
        VILLAGER_LIKE = classes.get("entity.VillagerLike");
        GET_AGE_STATE = handles.get("getAgeState");
        GET_VILLAGER_BRAIN = handles.get("getVillagerBrain");
        GET_PERSONALITY = handles.get("getPersonality");
        GET_PROFESSION_ID = handles.get("getProfessionId");
        GET_RESIDENCY = handles.get("getResidency");
        GET_HOME_VILLAGE = handles.get("getHomeVillage");
        VILLAGE_GET_ID = handles.get("village.getId");
        VILLAGE_GET_NAME = handles.get("village.getName");
        VILLAGE_GET_CENTER = handles.get("village.getCenter");
        VILLAGE_IS_WITHIN_BORDER = handles.get("village.isWithinBorder");
        VILLAGE_GET_RESIDENTS = handles.get("village.getResidents");
        VILLAGE_GET_RESIDENT_UUIDS = handles.get("village.getResidentsUUIDs");
        VILLAGE_GET_RESIDENT_NAMES = handles.get("village.getResidentNames");
        MANAGER_GET = handles.get("manager.get");
        MANAGER_GET_OR_EMPTY = handles.get("manager.getOrEmpty");
        MANAGER_FIND_NEAREST = handles.get("manager.findNearestVillage");
        FAMILY_TREE_GET = handles.get("familyTree.get");
        FAMILY_TREE_GET_OR_EMPTY = handles.get("familyTree.getOrEmpty");
        NODE_GET_NAME = handles.get("node.getName");
        MISSING = List.copyOf(missing);
        MISSING_OPTIONAL = List.copyOf(missingOptional);
        AVAILABLE = root != null && missing.isEmpty();
    }

    /**
     * Replays {@link #MANIFEST} against another class loader, resolving classes and members but
     * binding nothing. What {@code McaReflectProbeTest} runs against every supported MCA jar.
     */
    static Resolution resolveAgainst(ClassLoader loader) {
        List<String> missing = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        String root = detectRoot(loader);
        if (root == null) {
            return new Resolution(null, List.of(), List.of());
        }
        Map<String, Class<?>> classes = new HashMap<>();
        for (String suffix : TYPES) {
            classes.put(suffix, type(missing, root, suffix, loader));
        }
        for (Member member : MANIFEST) {
            Class<?> owner = classes.get(member.owner());
            if (owner == null) {
                continue; // its class already recorded a miss
            }
            try {
                owner.getMethod(member.name(), member.params());
            } catch (Throwable t) {
                (member.optional() ? missingOptional : missing).add(owner.getName() + '#' + member.name());
            }
        }
        return new Resolution(root, List.copyOf(missing), List.copyOf(missingOptional));
    }

    private McaReflect() {
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** The first root whose two sentinel classes both resolve, or null when MCA is absent/unknown. */
    private static String detectRoot(ClassLoader loader) {
        for (String candidate : SUPPORTED_ROOTS) {
            if (resolves(candidate + ".entity.VillagerEntityMCA", loader)
                    && resolves(candidate + ".server.world.data.VillageManager", loader)) {
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
    private static boolean resolves(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> type(List<String> missing, String root, String suffix, ClassLoader loader) {
        String name = root + '.' + suffix;
        try {
            return Class.forName(name, false, loader);
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

    /**
     * The same resolution, recorded on the <b>audited-but-optional</b> surface.
     *
     * <p>Two tiers rather than one, because the cost of a missing member is not the same for every
     * member. Everything resolved through {@link #method} is load-bearing: without it no deed can be
     * recorded correctly, so a miss switches the whole integration off and says which member moved.
     * The members here only enrich an interpretation that has a mandatory neutral fallback (§13.2),
     * and taking deed recording down for one of them would be a self-inflicted outage — §13.2 is
     * explicit that role sensitivity must not hold up the core.
     *
     * <p>So a miss here is still audited: it is resolved at startup, listed in
     * {@link #missingOptional()}, and reported by {@link #selfTest()} as a degraded interpretation.
     * The accessor returns its safe default, and if the member is present but no longer links, the
     * {@link LinkageError} discipline in {@code McaCompat} latches the integration off on the first
     * call exactly as it does for any other member.
     */
    private static MethodHandle optionalMethod(List<String> missingOptional, Class<?> owner,
                                               String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Method resolved = owner.getMethod(name, params);
            return MethodHandles.lookup().unreflect(resolved);
        } catch (Throwable t) {
            missingOptional.add(owner.getName() + '#' + name);
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

    /**
     * True when the optional profile-interpretation members resolved too, i.e. when a villager's
     * profession can be read. False means neutral role interpretation, never a broken integration.
     */
    public static boolean areProfileTraitsAvailable() {
        return AVAILABLE && GET_PROFESSION_ID != null;
    }

    /** Audited-but-optional members that did not resolve, for logging and diagnostics. */
    public static List<String> missingOptional() {
        return MISSING_OPTIONAL;
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
            if (!MISSING_OPTIONAL.isEmpty()) {
                // Not an error: every member below has a neutral fallback, and the core is unaffected.
                McaReputation.LOGGER.warn("[MCA: Reputation] Reduced villager interpretation: these "
                        + "optional MCA members did not resolve: {}. Profile interpretation falls back "
                        + "to neutral authored weights; deed recording and standing are unaffected.",
                        MISSING_OPTIONAL);
            }
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

    /**
     * The villager's profession as MCA itself reports it: {@code "minecraft:farmer"},
     * {@code "mca:guard"}.
     *
     * <p>MCA's own {@code getProfessionId()} rather than the vanilla villager data, for the reason
     * the class javadoc gives in reverse: MCA's method name survives obfuscation, while reaching
     * {@code getVillagerData().getProfession()} through reflection would need a hardcoded SRG name.
     * The method has the same signature on every MCA root this build supports — verified with
     * {@code javap} against 7.6.20 and 7.7.1-alpha.2 — and is resolved on the optional tier, so an
     * MCA that drops it costs role interpretation and nothing else.
     */
    public static Optional<String> professionId(Entity villager) throws Throwable {
        if (!AVAILABLE || GET_PROFESSION_ID == null || !isVillager(villager)) {
            return Optional.empty();
        }
        Object profession = GET_PROFESSION_ID.invoke(villager);
        return profession == null ? Optional.empty() : Optional.of(profession.toString());
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
