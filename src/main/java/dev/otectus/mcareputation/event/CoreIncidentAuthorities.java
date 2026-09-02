package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of {@link CoreIncidentAuthority} claims, and the one place they are consulted (§20, §25.1).
 *
 * <p>Empty on a standalone install, which is the case that has to stay free: {@link #isClaimed} is
 * called from inside {@code LivingHurtEvent}, which fires for every point of damage dealt anywhere in
 * the world. With no companion installed the whole question costs one {@code isEmpty()} on a list that
 * is never written to, and the JIT folds it away.
 *
 * <p>Modelled on {@link LegacyImportProviders}: a copy-on-write list, every external call isolated in
 * its own {@code try}, and one badly-behaved companion never able to take the others — or the server —
 * down with it.
 */
public final class CoreIncidentAuthorities {

    private static final List<Registration> AUTHORITIES = new CopyOnWriteArrayList<>();

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
        for (Registration registration : AUTHORITIES) {
            if (!registration.isActive()) {
                continue;
            }
            try {
                if (registration.authority().owns(kind)) {
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
        List<String> names = new ArrayList<>();
        for (Registration registration : AUTHORITIES) {
            if (!registration.isActive()) {
                continue;
            }
            try {
                if (registration.authority().owns(kind)) {
                    names.add(safeName(registration.authority()));
                }
            } catch (Throwable t) {
                names.add(safeName(registration.authority()) + " (threw)");
            }
        }
        return names;
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

    /** Drops every registration. For tests; a live server has no reason to call this. */
    public static void clear() {
        AUTHORITIES.clear();
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
