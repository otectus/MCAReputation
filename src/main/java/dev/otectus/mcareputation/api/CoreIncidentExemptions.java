package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Per-incident legal exemptions for the deeds this mod detects itself (capability version 1).
 *
 * <p>Independent of {@link CoreIncidentAuthority}. An authority takes a whole kind away from this
 * mod's detector; an exemption answers a narrower question about <em>one</em> hit or death while the
 * detector is still this mod's. The case it exists for is MCA: Crime handing villager assault and
 * killing back (its Reputation integration off, or degraded) while a Crime thief is attacking the
 * player: fighting back is lawful under Crime's rules, and without this query Reputation would charge
 * the player for a villager assault Crime already knows was self-defence.
 *
 * <p><b>Default is PASS.</b> With nobody registered, or with every provider answering {@code PASS},
 * nothing changes. A provider that throws is treated as {@code PASS} and warned about once, so a
 * broken companion can never suppress detection wholesale.
 *
 * <p>Only {@link CoreIncidentKind#MCA_VILLAGER_ASSAULT} and {@link CoreIncidentKind#MCA_VILLAGER_KILL}
 * may be exempted: those are the two kinds a companion can have lawful knowledge about. Registration
 * is bounded (16 providers) and idempotent by id, so a companion re-registering after a reload
 * replaces its own query rather than stacking a second one.
 *
 * <p>Advertised as {@link ReputationCapabilities#FEATURE_INCIDENT_EXEMPTIONS}. A companion checks
 * {@link #capabilityVersion()} before registering; a future incompatible change bumps that number.
 *
 * @since MCA: Reputation 0.6.1
 */
public final class CoreIncidentExemptions {

    /** What a provider says about one hit or death. */
    public enum Decision {
        /** Ordinary law applies; other providers are still asked. */
        PASS,
        /** This deed is lawful and must not be recorded. First EXEMPT wins. */
        EXEMPT
    }

    /**
     * A provider's judgement. Called on the server thread from inside a damage or death event, so it
     * must be cheap and must not call back into {@link McaReputationApi}.
     *
     * @param amount the final damage for an assault; {@code 0} for a death
     */
    @FunctionalInterface
    public interface Query {
        Decision evaluate(CoreIncidentKind kind, ServerPlayer actor, LivingEntity target,
                          DamageSource source, float amount);
    }

    /** The only kinds a provider may exempt: the two a companion can have lawful knowledge about. */
    public static final Set<CoreIncidentKind> EXEMPTABLE_KINDS =
            Set.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT, CoreIncidentKind.MCA_VILLAGER_KILL);

    /** Registration capacity; sixteen is generous for a family of six add-ons. */
    public static final int MAX_PROVIDERS = 16;

    private static final int CAPABILITY_VERSION = 1;

    private record Registration(ResourceLocation id, Set<CoreIncidentKind> kinds, Query query) {
    }

    private static final Map<ResourceLocation, Registration> QUERIES = new LinkedHashMap<>();
    private static final Set<ResourceLocation> WARNED = new java.util.HashSet<>();

    private CoreIncidentExemptions() {
    }

    /** The capability generation; bumped only for an incompatible change to {@link Query}. */
    public static int capabilityVersion() {
        return CAPABILITY_VERSION;
    }

    /**
     * Registers, or replaces, one provider's query.
     *
     * @throws IllegalArgumentException for a null argument, an empty kind set, or a kind outside
     *                                  {@link #EXEMPTABLE_KINDS}
     * @throws IllegalStateException    when {@link #MAX_PROVIDERS} distinct providers are already
     *                                  registered and this id is new
     */
    public static synchronized void register(ResourceLocation id, Set<CoreIncidentKind> kinds, Query query) {
        if (id == null || query == null || kinds == null || kinds.isEmpty()
                || !EXEMPTABLE_KINDS.containsAll(kinds)) {
            throw new IllegalArgumentException("Only villager assault and killing exemptions are supported");
        }
        if (!QUERIES.containsKey(id) && QUERIES.size() >= MAX_PROVIDERS) {
            throw new IllegalStateException("Exemption provider capacity (" + MAX_PROVIDERS + ") reached");
        }
        QUERIES.put(id, new Registration(id, Set.copyOf(kinds), query));
        WARNED.remove(id);
        McaReputation.LOGGER.info("[MCA: Reputation] '{}' registered incident exemptions for {}", id, kinds);
    }

    /** Withdraws a provider. Idempotent; {@code true} when something was removed. */
    public static synchronized boolean unregister(ResourceLocation id) {
        WARNED.remove(id);
        return id != null && QUERIES.remove(id) != null;
    }

    /** The registered provider ids, in registration order, for diagnostics. */
    public static synchronized List<ResourceLocation> registeredIds() {
        return List.copyOf(QUERIES.keySet());
    }

    /**
     * Whether any provider exempts this deed. Answers {@code false} for a null actor, target or
     * source, or when actor and target are not on the same server.
     */
    public static boolean exempt(CoreIncidentKind kind, ServerPlayer actor, LivingEntity target,
                                 DamageSource source, float amount) {
        if (kind == null || actor == null || target == null || source == null
                || actor.getServer() != target.getServer()) {
            return false;
        }
        return anyExempt(kind, query -> query.evaluate(kind, actor, target, source, amount));
    }

    /**
     * The consultation itself, with the invocation abstracted so it can be pinned without a server:
     * providers registered for {@code kind} are asked in registration order, the first {@code EXEMPT}
     * wins, and a provider that throws counts as {@code PASS} and is warned about once.
     */
    static boolean anyExempt(CoreIncidentKind kind, Function<Query, Decision> invoke) {
        List<Registration> snapshot;
        synchronized (CoreIncidentExemptions.class) {
            if (QUERIES.isEmpty()) {
                return false;
            }
            snapshot = new ArrayList<>(QUERIES.values());
        }
        for (Registration registration : snapshot) {
            if (!registration.kinds().contains(kind)) {
                continue;
            }
            try {
                if (invoke.apply(registration.query()) == Decision.EXEMPT) {
                    return true;
                }
            } catch (Throwable failure) {
                boolean first;
                synchronized (CoreIncidentExemptions.class) {
                    first = WARNED.add(registration.id());
                }
                if (first) {
                    McaReputation.LOGGER.warn("[MCA: Reputation] incident exemption provider {} failed; "
                            + "normal law applies", registration.id(), failure);
                }
            }
        }
        return false;
    }

    /** Drops every provider. Test and shutdown hook; companions re-register at their next setup. */
    public static synchronized void clear() {
        QUERIES.clear();
        WARNED.clear();
    }
}
