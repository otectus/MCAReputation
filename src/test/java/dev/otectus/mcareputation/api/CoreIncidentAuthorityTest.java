package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.event.CoreIncidentAuthorities;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core-incident authority handshake (§20, §25.1): the mechanism that lets MCA: Crime detect
 * villager assault and killing without this mod recording the same deed a second time.
 *
 * <p>The behaviours worth pinning are the ones whose failure is silent. A claim that cannot be
 * withdrawn, or one that survives its owner's config being switched off, disables villager detection
 * across the whole server with nothing in the log to connect it to.
 */
class CoreIncidentAuthorityTest {

    @AfterEach
    void tearDown() {
        CoreIncidentAuthorities.clear();
    }

    /** A claimant whose ownership is a mutable switch, exactly as a companion's config would be. */
    private static final class TestAuthority implements CoreIncidentAuthority {
        private final ResourceLocation id;
        boolean owning = true;
        boolean explode;

        TestAuthority(String path) {
            this.id = McaReputation.id(path);
        }

        @Override
        public ResourceLocation authorityId() {
            return id;
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            if (explode) {
                throw new IllegalStateException("a companion mod with a bug in one boolean");
            }
            return owning;
        }
    }

    @Test
    void nothingIsClaimedOnAStandaloneInstall() {
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            assertFalse(McaReputationApi.hasExternalAuthority(kind));
        }
    }

    @Test
    void aRegisteredAuthorityClaimsTheKindsItOwns() {
        CoreIncidentAuthorityRegistration registration =
                McaReputationApi.registerCoreIncidentAuthority(new TestAuthority("crime_detector"));
        assertTrue(registration.isActive());
        assertTrue(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
        assertTrue(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL));
    }

    /**
     * The reason ownership is asked per event rather than read once. A companion whose own detection
     * is switched off in config must hand the deed straight back, with no re-registration.
     */
    @Test
    void aClaimantThatStopsOwningHandsDetectionBackImmediately() {
        TestAuthority authority = new TestAuthority("crime_detector");
        McaReputationApi.registerCoreIncidentAuthority(authority);
        assertTrue(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));

        authority.owning = false;

        assertFalse(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT),
                "detection must return on the next event, not on the next restart");
    }

    @Test
    void closingTheRegistrationWithdrawsTheClaim() {
        CoreIncidentAuthorityRegistration registration =
                McaReputationApi.registerCoreIncidentAuthority(new TestAuthority("crime_detector"));
        registration.close();

        assertFalse(registration.isActive());
        assertFalse(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
        assertTrue(CoreIncidentAuthorities.registeredNames().isEmpty());
    }

    @Test
    void closingTwiceIsANoOpRatherThanAnError() {
        CoreIncidentAuthorityRegistration registration =
                McaReputationApi.registerCoreIncidentAuthority(new TestAuthority("crime_detector"));
        registration.close();
        registration.close();
        assertFalse(registration.isActive());
    }

    /**
     * A throwing claimant leaves detection here. Failing the other way would take villager assault
     * detection off the server entirely, with the deed recorded by nobody — a silent loss is worse
     * than a duplicate somebody can see in the ledger.
     */
    @Test
    void aThrowingAuthorityLeavesDetectionWithThisMod() {
        TestAuthority authority = new TestAuthority("crime_detector");
        authority.explode = true;
        McaReputationApi.registerCoreIncidentAuthority(authority);

        assertFalse(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
    }

    @Test
    void oneThrowingAuthorityDoesNotHideAWorkingOne() {
        TestAuthority broken = new TestAuthority("broken");
        broken.explode = true;
        TestAuthority working = new TestAuthority("working");
        McaReputationApi.registerCoreIncidentAuthority(broken);
        McaReputationApi.registerCoreIncidentAuthority(working);

        assertTrue(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL));
    }

    @Test
    void oneClaimantWithdrawingLeavesTheOtherStanding() {
        CoreIncidentAuthorityRegistration first =
                McaReputationApi.registerCoreIncidentAuthority(new TestAuthority("first"));
        McaReputationApi.registerCoreIncidentAuthority(new TestAuthority("second"));

        first.close();

        assertTrue(McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
        assertEquals(List.of(McaReputation.id("second").toString()),
                CoreIncidentAuthorities.registeredNames());
    }

    @Test
    void registeringNullIsRejectedRatherThanStored() {
        assertThrows(IllegalArgumentException.class,
                () -> McaReputationApi.registerCoreIncidentAuthority(null));
    }

    /**
     * A claimant is expected to file the same incident type this mod would have, so the ledger reads
     * identically whichever mod detected the deed.
     */
    @Test
    void everyKindNamesTheIncidentItWouldHaveProduced() {
        assertEquals(BuiltinIncidents.VILLAGER_ASSAULTED,
                CoreIncidentKind.MCA_VILLAGER_ASSAULT.incidentType());
        assertEquals(BuiltinIncidents.VILLAGER_KILLED,
                CoreIncidentKind.MCA_VILLAGER_KILL.incidentType());
    }

    @Test
    void incidentTypesMapBackToTheirKind() {
        assertEquals(Optional.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT),
                CoreIncidentKind.forIncident(BuiltinIncidents.VILLAGER_ASSAULTED));
        assertEquals(Optional.empty(), CoreIncidentKind.forIncident(BuiltinIncidents.QUEST_COMPLETED));
        assertEquals(Optional.empty(), CoreIncidentKind.forIncident(null));
    }

    /** Two kinds must never share an incident type, or a claim on one would silently cover the other. */
    @Test
    void kindsDoNotShareAnIncidentType() {
        long distinct = java.util.Arrays.stream(CoreIncidentKind.values())
                .map(CoreIncidentKind::incidentType).distinct().count();
        assertEquals(CoreIncidentKind.values().length, distinct);
    }
}
