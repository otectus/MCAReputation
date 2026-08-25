package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the external {@link CoreIncidentAuthority} claims and answers the one question the native
 * detectors ask: <em>should I step aside for this deed?</em> (integration spec §7.1).
 *
 * <h2>The rule, and why it is shaped this way</h2>
 *
 * <p>Reputation steps aside only when <b>exactly one</b> healthy authority claims the kind. Zero
 * claims means nobody else is producing it, so Reputation must. Two or more claims means the server
 * owner has an ambiguous setup, and there is no defensible way to pick a winner — so Reputation
 * keeps producing, an operator sees one error, and the worst case is a duplicate deed rather than a
 * deed that silently never existed. Losing a crime is worse than counting it twice: a duplicate is
 * visible and fixable, a black hole is neither.
 *
 * <p>{@code owns} is companion code called on the gameplay hot path, so every call is wrapped. A
 * throwing authority counts as not claiming, and its log line is rate-limited — a bridge that threw
 * once per hit would otherwise fill the log faster than the player can swing.
 */
public final class CoreIncidentAuthorityRegistry {

    /** One complaint per authority per this many milliseconds, so a broken bridge cannot flood the log. */
    private static final long THROW_LOG_INTERVAL_MS = 60_000L;

    private static final List<CoreIncidentAuthority> AUTHORITIES = new CopyOnWriteArrayList<>();
    private static final Map<ResourceLocation, AtomicLong> LAST_THROW_LOG = new ConcurrentHashMap<>();

    private CoreIncidentAuthorityRegistry() {
    }

    /**
     * Registers {@code authority}, or returns an inert handle if it is unusable (null, null id, or a
     * duplicate id). Rejection is logged once and never throws at the caller — a companion mod's
     * setup must not be able to crash Reputation's.
     */
    public static synchronized CoreIncidentAuthorityRegistration register(CoreIncidentAuthority authority) {
        if (authority == null) {
            McaReputation.LOGGER.error("[MCA: Reputation] A core-incident authority was registered as null; ignoring.");
            return inert(McaReputation.id("invalid"));
        }
        ResourceLocation id = safeId(authority);
        if (id == null) {
            McaReputation.LOGGER.error("[MCA: Reputation] A core-incident authority gave no usable id "
                    + "(null, or authorityId() threw); ignoring the registration.");
            return inert(McaReputation.id("invalid"));
        }
        for (CoreIncidentAuthority existing : AUTHORITIES) {
            if (id.equals(safeId(existing))) {
                McaReputation.LOGGER.error("[MCA: Reputation] A core-incident authority '{}' is already registered; "
                        + "the second registration is ignored. This usually means a bridge initialised twice.", id);
                return inert(id);
            }
        }
        AUTHORITIES.add(authority);
        McaReputation.LOGGER.info("[MCA: Reputation] Core-incident authority '{}' registered; native detection will "
                + "stand down for the kinds it claims.", id);
        return new Handle(id, authority);
    }

    /**
     * Whether exactly one healthy external authority claims {@code kind}, meaning the native detector
     * must not produce it.
     */
    public static boolean hasExternalAuthority(CoreIncidentKind kind) {
        if (kind == null || AUTHORITIES.isEmpty()) {
            return false;
        }
        CoreIncidentAuthority claimant = null;
        for (CoreIncidentAuthority authority : AUTHORITIES) {
            if (!claims(authority, kind)) {
                continue;
            }
            if (claimant != null) {
                McaReputation.LOGGER.error("[MCA: Reputation] Both '{}' and '{}' claim authority over {}. Ambiguous "
                                + "ownership cannot be resolved safely, so native detection stays ON — expect duplicate "
                                + "deeds until one of them is disabled.",
                        safeId(claimant), safeId(authority), kind);
                return false;
            }
            claimant = authority;
        }
        return claimant != null;
    }

    /** Every currently registered authority id, for diagnostics. */
    public static List<ResourceLocation> registeredIds() {
        return AUTHORITIES.stream()
                .map(CoreIncidentAuthorityRegistry::safeId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Drops every registration. Server shutdown and tests only. */
    public static synchronized void clear() {
        AUTHORITIES.clear();
        LAST_THROW_LOG.clear();
    }

    // ------------------------------------------------------------------ internals

    /** {@code owns} inside a guarded boundary: a throw is a fail-safe "no", logged at most once a minute. */
    private static boolean claims(CoreIncidentAuthority authority, CoreIncidentKind kind) {
        try {
            return authority.owns(kind);
        } catch (Throwable t) {
            ResourceLocation id = safeId(authority);
            if (id != null && shouldLogThrow(id)) {
                McaReputation.LOGGER.error("[MCA: Reputation] Core-incident authority '{}' threw from owns({}); "
                        + "treating it as unclaimed and keeping native detection on.", id, kind, t);
            }
            return false;
        }
    }

    private static boolean shouldLogThrow(ResourceLocation id) {
        long now = System.currentTimeMillis();
        AtomicLong last = LAST_THROW_LOG.computeIfAbsent(id, k -> new AtomicLong(0L));
        long previous = last.get();
        return now - previous >= THROW_LOG_INTERVAL_MS && last.compareAndSet(previous, now);
    }

    private static ResourceLocation safeId(CoreIncidentAuthority authority) {
        try {
            return authority.authorityId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static CoreIncidentAuthorityRegistration inert(ResourceLocation id) {
        return new CoreIncidentAuthorityRegistration() {
            @Override
            public ResourceLocation authorityId() {
                return id;
            }

            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class Handle implements CoreIncidentAuthorityRegistration {

        private final ResourceLocation id;
        private final CoreIncidentAuthority authority;
        private volatile boolean active = true;

        private Handle(ResourceLocation id, CoreIncidentAuthority authority) {
            this.id = id;
            this.authority = authority;
        }

        @Override
        public ResourceLocation authorityId() {
            return id;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public synchronized void close() {
            if (!active) {
                return;
            }
            active = false;
            AUTHORITIES.remove(authority);
            LAST_THROW_LOG.remove(id);
            McaReputation.LOGGER.info("[MCA: Reputation] Core-incident authority '{}' withdrawn; native detection "
                    + "resumes for the kinds it owned.", id);
        }
    }
}
