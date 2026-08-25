package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.reputation.CoreIncidentAuthorityRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core-incident authority truth table (integration spec §17.2).
 *
 * <p>Every row here answers the same question — <em>who produces this deed?</em> — and the property
 * being defended is that the answer is always exactly one party. Two producers charge the player
 * twice for one swing; zero producers is an incident black hole that no log line explains. Of the
 * two, the black hole is far worse, so every ambiguous or broken case must resolve to "Reputation
 * keeps producing".
 */
class CoreIncidentAuthorityTest {

    private static final ResourceLocation CRIME = new ResourceLocation("mcacrime", "crime_detector");
    private static final ResourceLocation OTHER = new ResourceLocation("othermod", "detector");

    /** A stub companion authority whose health and claims the test drives directly. */
    private record Stub(ResourceLocation id, boolean healthy) implements CoreIncidentAuthority {
        @Override
        public ResourceLocation authorityId() {
            return id;
        }

        @Override
        public boolean owns(CoreIncidentKind kind) {
            return healthy;
        }
    }

    @BeforeEach
    @AfterEach
    void clearRegistry() {
        CoreIncidentAuthorityRegistry.clear();
    }

    // ------------------------------------------------------------------ the truth table

    @Test
    void noAuthorityLeavesNativeDetectionOn() {
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(kind),
                    kind + " must be produced natively when nobody has claimed it");
        }
    }

    @Test
    void oneHealthyAuthorityTakesOver() {
        CoreIncidentAuthorityRegistration handle =
                CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));

        assertTrue(handle.isActive());
        assertEquals(CRIME, handle.authorityId());
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            assertTrue(CoreIncidentAuthorityRegistry.hasExternalAuthority(kind));
        }
    }

    @Test
    void aRegisteredButUnhealthyAuthorityDoesNotClaim() {
        // The "present but disabled" and "present but incompatible" rows: the bridge registered, then
        // its handshake failed or its config was switched off. It must answer false, and Reputation
        // must keep producing rather than trusting the registration itself.
        CoreIncidentAuthorityRegistry.register(new Stub(CRIME, false));

        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL));
    }

    @Test
    void aThrowingAuthorityFailsSafeToNativeDetection() {
        CoreIncidentAuthorityRegistry.register(new CoreIncidentAuthority() {
            @Override
            public ResourceLocation authorityId() {
                return CRIME;
            }

            @Override
            public boolean owns(CoreIncidentKind kind) {
                throw new IllegalStateException("bridge is broken");
            }
        });

        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT),
                "a throwing owns() must read as unclaimed, not as ownership");
    }

    @Test
    void twoClaimantsAreAmbiguousSoNativeDetectionStaysOn() {
        CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));
        CoreIncidentAuthorityRegistry.register(new Stub(OTHER, true));

        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT),
                "there is no defensible way to pick a winner, so neither is accepted as exclusive");
        assertEquals(2, CoreIncidentAuthorityRegistry.registeredIds().size());
    }

    @Test
    void ambiguityClearsWhenOneClaimantGoesQuiet() {
        CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));
        CoreIncidentAuthorityRegistration other = CoreIncidentAuthorityRegistry.register(new Stub(OTHER, true));
        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL));

        other.close();

        assertTrue(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL),
                "with the ambiguity gone the single remaining claimant owns the kind again");
    }

    // ------------------------------------------------------------------ registration hygiene

    @Test
    void aDuplicateIdIsRejectedAndTheFirstRegistrationSurvives() {
        CoreIncidentAuthorityRegistration first = CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));
        CoreIncidentAuthorityRegistration second = CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));

        assertTrue(first.isActive());
        assertFalse(second.isActive(), "the duplicate must come back inert rather than as a second claimant");
        assertEquals(1, CoreIncidentAuthorityRegistry.registeredIds().size());
        // Critically, the rejected duplicate must not have created ambiguity that switches detection
        // back on — a bridge that initialised twice is still exactly one producer.
        assertTrue(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
    }

    @Test
    void aNullAuthorityIsRejectedWithoutThrowing() {
        CoreIncidentAuthorityRegistration handle = CoreIncidentAuthorityRegistry.register(null);

        assertFalse(handle.isActive());
        assertEquals(0, CoreIncidentAuthorityRegistry.registeredIds().size());
    }

    @Test
    void anAuthorityThatThrowsFromAuthorityIdIsRejected() {
        CoreIncidentAuthorityRegistration handle = CoreIncidentAuthorityRegistry.register(
                new CoreIncidentAuthority() {
                    @Override
                    public ResourceLocation authorityId() {
                        throw new IllegalStateException("no id");
                    }

                    @Override
                    public boolean owns(CoreIncidentKind kind) {
                        return true;
                    }
                });

        assertFalse(handle.isActive());
        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
    }

    @Test
    void closingIsIdempotentAndRestoresNativeDetection() {
        CoreIncidentAuthorityRegistration handle =
                CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));
        assertTrue(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));

        handle.close();
        handle.close();

        assertFalse(handle.isActive());
        assertEquals(0, CoreIncidentAuthorityRegistry.registeredIds().size());
        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
    }

    @Test
    void ownershipIsCheckedPerKindNotPerRegistration() {
        // A companion may own assault while leaving killing to Reputation. Registration alone must
        // never be read as a claim on everything.
        CoreIncidentAuthorityRegistry.register(new CoreIncidentAuthority() {
            @Override
            public ResourceLocation authorityId() {
                return CRIME;
            }

            @Override
            public boolean owns(CoreIncidentKind kind) {
                return kind == CoreIncidentKind.MCA_VILLAGER_ASSAULT;
            }
        });

        assertTrue(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT));
        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_KILL));
    }

    @Test
    void aNullKindNeverClaimsOwnership() {
        CoreIncidentAuthorityRegistry.register(new Stub(CRIME, true));

        assertFalse(CoreIncidentAuthorityRegistry.hasExternalAuthority(null));
    }
}
