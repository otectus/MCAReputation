package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentAuthorityRegistration;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effective claim (F01): who a registered authority is actually honoured for, kind by kind.
 *
 * <p>{@code CoreIncidentAuthorityTest} covers the handshake itself with an authority that declares
 * its kinds. This covers the case that shipped broken: an authority written against 0.3.0, when only
 * villager assault and killing existed, answering {@code owns(kind) == true} for four kinds it has
 * never heard of. Honouring that claim stands this mod's own detection down for rescue, cure, raid
 * and PvP and hands them to a companion that will never file them — nobody records the deed, and the
 * only visible symptom is a village that shrugs at being saved from a raid.
 *
 * <p>Every assertion runs against an explicitly supplied {@link ReputationPolicy} rather than the
 * config spec, which is not loaded in a plain JUnit run.
 */
class CoreAuthorityTest {

    private static final ReputationPolicy DEFAULT_POLICY = ReputationPolicy.defaults();

    @AfterEach
    void tearDown() {
        CoreIncidentAuthorities.clear();
    }

    /** An authority that claims everything and declares nothing: exactly the 0.3.0 shape. */
    private static final class LegacyAuthority implements CoreIncidentAuthority {
        private final ResourceLocation id;
        boolean deliverable = true;
        int serverStops;

        LegacyAuthority(String path) {
            this.id = McaReputation.id(path);
        }

        @Override
        public ResourceLocation authorityId() {
            return id;
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            return true;
        }

        @Override
        public boolean canDeliver(CoreIncidentKind kind) {
            return deliverable;
        }

        @Override
        public void onServerStopped() {
            serverStops++;
        }
    }

    /** An authority written against this version: it says which kinds it detects. */
    private static final class DeclaringAuthority implements CoreIncidentAuthority {
        private final ResourceLocation id;
        private final Set<CoreIncidentKind> declared;

        DeclaringAuthority(String path, Set<CoreIncidentKind> declared) {
            this.id = McaReputation.id(path);
            this.declared = declared;
        }

        @Override
        public ResourceLocation authorityId() {
            return id;
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            return true;
        }

        @Override
        public Optional<Set<CoreIncidentKind>> declaredKinds() {
            return Optional.of(declared);
        }
    }

    private static Set<CoreIncidentKind> claimedUnder(ReputationPolicy policy) {
        Set<CoreIncidentKind> claimed = EnumSet.noneOf(CoreIncidentKind.class);
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            if (CoreIncidentAuthorities.isClaimed(kind, policy)) {
                claimed.add(kind);
            }
        }
        return claimed;
    }

    // ------------------------------------------------------------------
    // T01 — the legacy claim, kind by kind
    // ------------------------------------------------------------------

    /**
     * Iterates every member of the enum rather than naming four: a seventh kind added later must be
     * unclaimed by an undeclared authority the day it is added, without anyone remembering to say so.
     */
    @Test
    void anUndeclaredAuthorityIsHonouredOnlyForTheTwoKindsThatExistedWhenItCouldHaveBeenWritten() {
        CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        assertEquals(EnumSet.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT, CoreIncidentKind.MCA_VILLAGER_KILL),
                claimedUnder(DEFAULT_POLICY),
                "an authority that declares nothing claims only the pre-declaration kinds");
    }

    @Test
    void anUndeclaredAuthorityStillNamesItselfAsTheClaimantOfThoseTwo() {
        CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        assertEquals(List.of(McaReputation.id("legacy_crime").toString()),
                CoreIncidentAuthorities.claimantsOf(CoreIncidentKind.MCA_VILLAGER_ASSAULT, DEFAULT_POLICY));
        assertEquals(List.of(),
                CoreIncidentAuthorities.claimantsOf(CoreIncidentKind.MCA_VILLAGER_RESCUE, DEFAULT_POLICY));
    }

    // ------------------------------------------------------------------
    // T02 — declaration, deliverability, policy, withdrawal, server stop
    // ------------------------------------------------------------------

    @Test
    void aDeclaredSetIsClaimedExactlyAsDeclared() {
        Set<CoreIncidentKind> declared =
                EnumSet.of(CoreIncidentKind.MCA_VILLAGER_RESCUE, CoreIncidentKind.MCA_RAID_REPELLED);
        CoreIncidentAuthorities.register(new DeclaringAuthority("crime", declared));

        assertEquals(declared, claimedUnder(DEFAULT_POLICY),
                "declaring opts out of the undeclared gate in both directions");
    }

    @Test
    void anAuthorityThatCannotDeliverClaimsNothingWhileStillRegistered() {
        LegacyAuthority authority = new LegacyAuthority("legacy_crime");
        CoreIncidentAuthorityRegistration registration = CoreIncidentAuthorities.register(authority);
        assertTrue(CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_ASSAULT, DEFAULT_POLICY));

        authority.deliverable = false;

        assertEquals(EnumSet.noneOf(CoreIncidentKind.class), claimedUnder(DEFAULT_POLICY),
                "a claimant that cannot file the incident hands detection back for as long as that lasts");
        assertTrue(registration.isActive(), "and does so without withdrawing its registration");
    }

    /** The escape hatch for an operator who wants 0.4.0's behaviour back. */
    @Test
    void trustLegacyHonoursEveryKindAnUndeclaredAuthorityClaims() {
        CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        assertEquals(EnumSet.allOf(CoreIncidentKind.class), claimedUnder(DEFAULT_POLICY.withUndeclaredAuthorityMode(
                ReputationPolicy.UndeclaredAuthorityMode.TRUST_LEGACY)));
    }

    @Test
    void ignoreHonoursNothingAnUndeclaredAuthorityClaims() {
        CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        assertEquals(EnumSet.noneOf(CoreIncidentKind.class), claimedUnder(DEFAULT_POLICY.withUndeclaredAuthorityMode(
                ReputationPolicy.UndeclaredAuthorityMode.IGNORE)));
    }

    @Test
    void theModeDoesNotReachAnAuthorityThatDeclaredItsKinds() {
        Set<CoreIncidentKind> declared = EnumSet.of(CoreIncidentKind.MCA_VILLAGER_CURE);
        CoreIncidentAuthorities.register(new DeclaringAuthority("crime", declared));

        assertEquals(declared, claimedUnder(DEFAULT_POLICY.withUndeclaredAuthorityMode(
                ReputationPolicy.UndeclaredAuthorityMode.IGNORE)));
        assertEquals(declared, claimedUnder(DEFAULT_POLICY.withUndeclaredAuthorityMode(
                ReputationPolicy.UndeclaredAuthorityMode.TRUST_LEGACY)));
    }

    @Test
    void closingTheRegistrationLeavesEveryKindUnclaimed() {
        CoreIncidentAuthorityRegistration registration =
                CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        registration.close();

        assertEquals(EnumSet.noneOf(CoreIncidentKind.class), claimedUnder(DEFAULT_POLICY));
    }

    /**
     * DD10: server stop is a world boundary, not a registration boundary. Companions register once
     * per JVM from common setup, so releasing the handle here would leave them silently unregistered
     * in the second world opened in the same process — a bug nobody meets in a dedicated server.
     */
    @Test
    void clearServerScopedNotifiesEachAuthorityOnceAndKeepsTheRegistration() {
        LegacyAuthority first = new LegacyAuthority("first");
        LegacyAuthority second = new LegacyAuthority("second");
        CoreIncidentAuthorities.register(first);
        CoreIncidentAuthorities.register(second);

        CoreIncidentAuthorities.clearServerScoped();

        assertEquals(1, first.serverStops);
        assertEquals(1, second.serverStops);
        assertEquals(2, CoreIncidentAuthorities.registeredNames().size(),
                "the claims survive into the next world loaded in this JVM");
        assertTrue(CoreIncidentAuthorities.isClaimed(CoreIncidentKind.MCA_VILLAGER_KILL, DEFAULT_POLICY));
    }

    // ------------------------------------------------------------------
    // The diagnostic the operator actually reads
    // ------------------------------------------------------------------

    @Test
    void inspectSeparatesAnUnhonouredClaimFromNativeDetection() {
        CoreIncidentAuthorities.register(new LegacyAuthority("legacy_crime"));

        List<CoreIncidentAuthorities.AuthorityStatus> statuses =
                CoreIncidentAuthorities.inspect(DEFAULT_POLICY);
        assertEquals(CoreIncidentKind.values().length, statuses.size());

        CoreIncidentAuthorities.AuthorityStatus rescue = statuses.stream()
                .filter(status -> status.kind() == CoreIncidentKind.MCA_VILLAGER_RESCUE)
                .findFirst().orElseThrow();
        assertFalse(rescue.claimed());
        assertFalse(rescue.declared());
        assertTrue(rescue.canDeliver());
        assertTrue(rescue.claimantId().isPresent(),
                "the claim exists and is refused; an operator has to be able to tell that from silence");

        CoreIncidentAuthorities.AuthorityStatus assault = statuses.stream()
                .filter(status -> status.kind() == CoreIncidentKind.MCA_VILLAGER_ASSAULT)
                .findFirst().orElseThrow();
        assertTrue(assault.claimed());
    }

    @Test
    void inspectReportsNoClaimantAtAllOnAStandaloneInstall() {
        for (CoreIncidentAuthorities.AuthorityStatus status : CoreIncidentAuthorities.inspect(DEFAULT_POLICY)) {
            assertFalse(status.claimed());
            assertTrue(status.claimantId().isEmpty());
            assertTrue(status.unavailableReason().isEmpty());
        }
    }
}
