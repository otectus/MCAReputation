package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the exemption registry's bounds and consultation rules without a server or a level. */
class CoreIncidentExemptionsTest {

    private static final ResourceLocation CRIME = new ResourceLocation("mcacrime", "thief_combat");
    private static final ResourceLocation OTHER = new ResourceLocation("examplemod", "duels");

    @BeforeEach
    @AfterEach
    void reset() {
        CoreIncidentExemptions.clear();
    }

    @Test
    void capabilityVersionIsOne() {
        assertEquals(1, CoreIncidentExemptions.capabilityVersion());
    }

    @Test
    void nothingRegisteredMeansPass() {
        assertFalse(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_ASSAULT,
                query -> CoreIncidentExemptions.Decision.EXEMPT));
        assertFalse(CoreIncidentExemptions.exempt(CoreIncidentKind.MCA_VILLAGER_ASSAULT, null, null, null, 1f));
    }

    @Test
    void onlyVillagerHarmKindsMayBeExempted() {
        CoreIncidentExemptions.Query pass = (kind, actor, target, source, amount) ->
                CoreIncidentExemptions.Decision.PASS;
        assertThrows(IllegalArgumentException.class, () -> CoreIncidentExemptions.register(CRIME,
                Set.of(CoreIncidentKind.MCA_VILLAGER_RESCUE), pass));
        assertThrows(IllegalArgumentException.class, () -> CoreIncidentExemptions.register(CRIME, Set.of(), pass));
        assertThrows(IllegalArgumentException.class, () -> CoreIncidentExemptions.register(null,
                Set.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT), pass));
        assertThrows(IllegalArgumentException.class, () -> CoreIncidentExemptions.register(CRIME,
                Set.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT), null));
        assertEquals(List.of(), CoreIncidentExemptions.registeredIds());
    }

    @Test
    void firstExemptWinsAndKindsAreFiltered() {
        CoreIncidentExemptions.register(OTHER, Set.of(CoreIncidentKind.MCA_VILLAGER_KILL),
                (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.EXEMPT);
        CoreIncidentExemptions.register(CRIME, Set.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT),
                (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.EXEMPT);

        assertTrue(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_ASSAULT,
                query -> query.evaluate(CoreIncidentKind.MCA_VILLAGER_ASSAULT, null, null, null, 0f)));
        assertTrue(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_KILL,
                query -> query.evaluate(CoreIncidentKind.MCA_VILLAGER_KILL, null, null, null, 0f)));
        assertEquals(List.of(OTHER, CRIME), CoreIncidentExemptions.registeredIds());
    }

    @Test
    void passingProvidersLeaveNormalLaw() {
        CoreIncidentExemptions.register(CRIME, CoreIncidentExemptions.EXEMPTABLE_KINDS,
                (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.PASS);
        assertFalse(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_ASSAULT,
                query -> query.evaluate(CoreIncidentKind.MCA_VILLAGER_ASSAULT, null, null, null, 0f)));
    }

    @Test
    void throwingProviderCountsAsPassAndOthersAreStillAsked() {
        AtomicInteger asked = new AtomicInteger();
        CoreIncidentExemptions.register(OTHER, CoreIncidentExemptions.EXEMPTABLE_KINDS,
                (kind, actor, target, source, amount) -> {
                    throw new IllegalStateException("broken companion");
                });
        CoreIncidentExemptions.register(CRIME, CoreIncidentExemptions.EXEMPTABLE_KINDS,
                (kind, actor, target, source, amount) -> {
                    asked.incrementAndGet();
                    return CoreIncidentExemptions.Decision.EXEMPT;
                });
        assertTrue(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_KILL,
                query -> query.evaluate(CoreIncidentKind.MCA_VILLAGER_KILL, null, null, null, 0f)));
        assertEquals(1, asked.get());
        // A second failure must not throw either; the warning is logged once.
        assertTrue(CoreIncidentExemptions.anyExempt(CoreIncidentKind.MCA_VILLAGER_KILL,
                query -> query.evaluate(CoreIncidentKind.MCA_VILLAGER_KILL, null, null, null, 0f)));
    }

    @Test
    void registrationIsIdempotentByIdAndBounded() {
        for (int i = 0; i < CoreIncidentExemptions.MAX_PROVIDERS; i++) {
            CoreIncidentExemptions.register(new ResourceLocation("examplemod", "p" + i),
                    CoreIncidentExemptions.EXEMPTABLE_KINDS,
                    (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.PASS);
        }
        // Re-registering an existing id replaces it and needs no capacity.
        CoreIncidentExemptions.register(new ResourceLocation("examplemod", "p0"),
                CoreIncidentExemptions.EXEMPTABLE_KINDS,
                (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.EXEMPT);
        assertEquals(CoreIncidentExemptions.MAX_PROVIDERS, CoreIncidentExemptions.registeredIds().size());
        assertThrows(IllegalStateException.class, () -> CoreIncidentExemptions.register(CRIME,
                CoreIncidentExemptions.EXEMPTABLE_KINDS,
                (kind, actor, target, source, amount) -> CoreIncidentExemptions.Decision.PASS));
        assertTrue(CoreIncidentExemptions.unregister(new ResourceLocation("examplemod", "p0")));
        assertFalse(CoreIncidentExemptions.unregister(new ResourceLocation("examplemod", "p0")));
        assertEquals(CoreIncidentExemptions.MAX_PROVIDERS - 1, CoreIncidentExemptions.registeredIds().size());
    }
}
