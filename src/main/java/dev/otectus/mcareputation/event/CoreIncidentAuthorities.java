package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.reputation.ReputationPolicy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of {@link CoreIncidentAuthority} claims, and the one place they are consulted (§20, §25.1).
 *
 * <p>Empty on a standalone install, which is the case that has to stay free: {@link #isClaimed} is
 * called from inside {@code LivingDamageEvent.Post}, which fires for every point of damage dealt
 * anywhere in the world. With no companion installed the whole question costs one {@code isEmpty()}
 * on a list that is never written to, and the JIT folds it away.
 *
 * <p>Modelled on {@link LegacyImportProviders}: a copy-on-write list, every external call isolated in
 * its own {@code try}, and one badly-behaved companion never able to take the others — or the server —
 * down with it.
 */
public final class CoreIncidentAuthorities {

    private static final List<Registration> AUTHORITIES = new CopyOnWriteArrayList<>();

    /** The two kinds that existed when an undeclared authority could have been written (0.3.0). */
    private static final Set<CoreIncidentKind> LEGACY_KINDS =
            EnumSet.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT, CoreIncidentKind.MCA_VILLAGER_KILL);

    private CoreIncidentAuthorities() {
    }

    /** Registers a claim and returns the handle that withdraws it. */
    public static CoreIncidentAuthorityRegistration register(CoreIncidentAuthority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("CoreIncidentAuthority must not be null");
        }
        Registration registration = new Registration(authority);
        AUTHORITIES.add(registration);
        McaReputation.LOGGER.info("[MCA: Reputation] '{}' registered as a core incident authority; "
                        + "this mod will stand down from detecting the kinds it claims",
                safeName(authority));
        return registration;
    }

    /**
     * Whether some companion is currently detecting this kind, so this mod should not.
     *
     * <p>An authority that throws is treated as <b>not</b> owning the kind, and the direction matters.
     * Failing the other way would mean a companion with a bug in one boolean silently switched off
     * villager assault detection across the whole server, with the deed recorded by nobody and no
     * error anybody would connect to it. Failing this way risks the opposite — both mods recording one
     * punch — which is visible in the ledger the moment it happens, and therefore fixable. A silent
     * loss is worse than a loud duplicate.
     */
    public static boolean isClaimed(CoreIncidentKind kind) {
        if (AUTHORITIES.isEmpty() || kind == null) {
            return false;
        }
        return isClaimed(kind, McaReputationConfig.snapshot());
    }

    /** The same question against an explicit policy, so a test never depends on a loaded spec. */
    static boolean isClaimed(CoreIncidentKind kind, ReputationPolicy policy) {
        if (AUTHORITIES.isEmpty() || kind == null) {
            return false;
        }
        for (Registration registration : AUTHORITIES) {
            try {
                if (claims(registration, kind, policy)) {
                    return true;
                }
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] core incident authority '{}' threw while being "
                                + "asked about {}; treating it as not claimed, so detection stays with this mod",
                        safeName(registration.authority()), kind, t);
            }
        }
        return false;
    }

    /** Who is claiming this kind right now, for {@code /mcareputation debug authorities}. */
    public static List<String> claimantsOf(CoreIncidentKind kind) {
        return claimantsOf(kind, McaReputationConfig.snapshot());
    }

    static List<String> claimantsOf(CoreIncidentKind kind, ReputationPolicy policy) {
        List<String> names = new ArrayList<>();
        for (Registration registration : AUTHORITIES) {
            try {
                if (claims(registration, kind, policy)) {
                    names.add(safeName(registration.authority()));
                }
            } catch (Throwable t) {
                names.add(safeName(registration.authority()) + " (threw)");
            }
        }
        return names;
    }

    /**
     * The effective claim for one registration: live, owning, able to deliver, and either declaring
     * this kind outright or allowed it by the undeclared-authority policy.
     *
     * <p>The last clause is the point. An authority written against 0.3.0 answers {@code true} from
     * {@code owns} for kinds that did not exist when it was written, so honouring a blanket claim
     * takes rescue, cure, raid and PvP detection away from this mod and hands them to a companion
     * that has never heard of them - and nobody records those deeds at all.
     */
    private static boolean claims(Registration registration, CoreIncidentKind kind, ReputationPolicy policy) {
        if (!registration.isActive() || kind == null) {
            return false;
        }
        CoreIncidentAuthority authority = registration.authority();
        if (!authority.owns(kind) || !authority.canDeliver(kind)) {
            return false;
        }
        Optional<Set<CoreIncidentKind>> declared = authority.declaredKinds();
        if (declared != null && declared.isPresent()) {
            Set<CoreIncidentKind> kinds = declared.get();
            return kinds != null && kinds.contains(kind);
        }
        return undeclaredAllows(kind, policy);
    }

    /** What an authority that declared nothing is still trusted with. */
    private static boolean undeclaredAllows(CoreIncidentKind kind, ReputationPolicy policy) {
        ReputationPolicy.UndeclaredAuthorityMode mode = policy == null
                ? ReputationPolicy.DEFAULT_UNDECLARED_AUTHORITY_MODE
                : policy.undeclaredAuthorityMode();
        return switch (mode) {
            case TRUST_LEGACY -> true;
            case ASSAULT_KILL_ONLY -> LEGACY_KINDS.contains(kind);
            case IGNORE -> false;
        };
    }

    /** One kind's whole story, for {@code /mcareputation debug authority}. */
    public record AuthorityStatus(CoreIncidentKind kind,
                                  boolean claimed,
                                  Optional<String> claimantId,
                                  boolean declared,
                                  boolean canDeliver,
                                  Optional<String> unavailableReason) {
    }

    /** Every kind, and what this mod currently believes about who detects it. */
    public static List<AuthorityStatus> inspect() {
        return inspect(McaReputationConfig.snapshot());
    }

    static List<AuthorityStatus> inspect(ReputationPolicy policy) {
        List<AuthorityStatus> statuses = new ArrayList<>();
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            statuses.add(inspect(kind, policy));
        }
        return statuses;
    }

    private static AuthorityStatus inspect(CoreIncidentKind kind, ReputationPolicy policy) {
        for (Registration registration : AUTHORITIES) {
            if (!registration.isActive() || !safeOwns(registration, kind)) {
                continue;
            }
            // The first owner is the one an operator is asking about; a second is a misconfiguration
            // that `debug authorities` already lists in full.
            CoreIncidentAuthority authority = registration.authority();
            boolean declared;
            try {
                Optional<Set<CoreIncidentKind>> kinds = authority.declaredKinds();
                declared = kinds != null && kinds.isPresent();
            } catch (Throwable t) {
                declared = false;
            }
            boolean canDeliver;
            try {
                canDeliver = authority.canDeliver(kind);
            } catch (Throwable t) {
                canDeliver = false;
            }
            boolean claimed;
            try {
                claimed = claims(registration, kind, policy);
            } catch (Throwable t) {
                claimed = false;
            }
            return new AuthorityStatus(kind, claimed, Optional.of(safeName(authority)), declared,
                    canDeliver, safeUnavailableReason(registration));
        }
        return new AuthorityStatus(kind, false, Optional.empty(), false, false, Optional.empty());
    }

    private static boolean safeOwns(Registration registration, CoreIncidentKind kind) {
        try {
            return registration.authority().owns(kind);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The claimant's own diagnostic, contained: it comes from the companion too. */
    private static Optional<String> safeUnavailableReason(Registration registration) {
        try {
            Optional<String> reason = registration.unavailableReason();
            return reason == null ? Optional.empty() : reason;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Every live registration's name, claimed or not. */
    public static List<String> registeredNames() {
        List<String> names = new ArrayList<>();
        for (Registration registration : AUTHORITIES) {
            if (registration.isActive()) {
                names.add(safeName(registration.authority()));
            }
        }
        return names;
    }

    /** Drops every registration. Test-only; a live server calls {@link #clearServerScoped()}. */
    public static void clear() {
        AUTHORITIES.clear();
    }

    /**
     * Server stop: tell every authority the world went away, and keep the registration.
     *
     * <p>Companions register once per JVM from common setup, so dropping the handles here would leave
     * them silently unregistered in the second world loaded in the same process - a bug that only
     * appears when somebody returns to the menu and opens another save. There is no memoized claim to
     * discard; the effective claim is computed per call.
     */
    public static void clearServerScoped() {
        for (Registration registration : AUTHORITIES) {
            try {
                registration.authority().onServerStopped();
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] core incident authority '{}' threw while being "
                                + "told the server stopped; its registration is kept",
                        safeName(registration.authority()), t);
            }
        }
    }

    /** A name for logs that cannot itself throw, since the name comes from the companion too. */
    private static String safeName(CoreIncidentAuthority authority) {
        try {
            String name = authority.authorityName();
            return name == null || name.isBlank() ? authority.getClass().getName() : name;
        } catch (Throwable t) {
            return authority.getClass().getName();
        }
    }

    /**
     * One claim. Removes itself from the list on close, so a withdrawn claim costs nothing to skip and
     * a companion that closes and re-registers does not accumulate dead entries.
     */
    private static final class Registration implements CoreIncidentAuthorityRegistration {

        private final CoreIncidentAuthority authority;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(CoreIncidentAuthority authority) {
            this.authority = authority;
        }

        @Override
        public CoreIncidentAuthority authority() {
            return authority;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            // compareAndSet, not a plain write: close() is documented idempotent, and this makes the
            // log line fire once rather than once per call.
            if (active.compareAndSet(true, false)) {
                AUTHORITIES.remove(this);
                McaReputation.LOGGER.info("[MCA: Reputation] '{}' withdrew its core incident authority; "
                        + "this mod resumes detection", safeName(authority));
            }
        }
    }
}
