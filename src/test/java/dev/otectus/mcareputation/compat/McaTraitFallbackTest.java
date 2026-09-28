package dev.otectus.mcareputation.compat;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.profile.VillagerProfileResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The compatibility seam behind villager interpretation, checked against the MCA this build pins.
 *
 * <p>The pinned MCA is a {@code runtimeOnly} dependency, which the Java plugin also puts on the test
 * runtime classpath. Nothing here compiles against it — {@code OptionalClassloadTest} is the
 * assertion that no class so much as names an MCA type — but {@link McaReflect}'s probe runs against
 * the real jar in this JVM, which makes this the one place a renamed MCA member can be caught by
 * {@code check} instead of by a player. Override the pin to exercise the other supported package
 * root: {@code ./gradlew test -Pmca_version=7.6.20+1.20.1}.
 *
 * <p>The resolution test skips rather than fails when no supported MCA is on the classpath, because
 * an offline or MCA-less build is a legitimate way to run this suite; the fallback tests below assert
 * the behaviour that has to hold either way. This is also a guard on the initialiser: resolution runs
 * in a static block that must not throw whatever it finds, and a regression there would surface here
 * as an {@code ExceptionInInitializerError} rather than as a quiet {@code false}.
 */
class McaTraitFallbackTest {

    private static final Path SOURCE =
            Paths.get("src/main/java/dev/otectus/mcareputation/compat/McaReflect.java");

    @Test
    void theTraitMembersResolveAgainstTheInstalledMca() {
        assumeTrue(McaReflect.isAvailable(),
                "no supported MCA on the test runtime classpath; nothing to resolve against");
        assertTrue(McaReflect.SUPPORTED_ROOTS.contains(McaReflect.root()),
                "the detected root must be one this build claims to support");
        assertTrue(McaReflect.areProfileTraitsAvailable(),
                "the villager profession member must resolve against the pinned MCA: "
                        + McaReflect.missingOptional());
        assertTrue(McaReflect.missingOptional().isEmpty(),
                "and nothing on the optional surface may be silently missing");
    }

    /**
     * §13.2's rule that role sensitivity must not hold up the core, asserted where it is decided.
     *
     * <p>A source assertion rather than a behavioural one because the failure it guards against
     * cannot be produced from inside this JVM: it needs an MCA that has dropped one member. The two
     * lines below are the whole discipline — the profession member is resolved on the optional list,
     * and availability is computed from the required list alone — so folding the optional list into
     * {@code AVAILABLE} would take deed recording down for a cosmetic weight.
     */
    @Test
    void aMissingOptionalMemberMustNotSwitchTheWholeIntegrationOff() throws IOException {
        if (!Files.isReadable(SOURCE)) {
            return; // running from a packaged artifact rather than the source tree
        }
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        // Since 0.6.1 the tier is declared in the manifest the initialiser binds from, so it is asserted
        // on the manifest itself rather than on the shape of one resolution line.
        assertTrue(McaReflect.MANIFEST.stream()
                        .filter(member -> member.name().equals("getProfessionId"))
                        .findFirst().map(McaReflect.Member::optional).orElse(false),
                "the profession member belongs on the audited-but-optional tier");
        assertTrue(source.contains("? optionalMethod(missingOptional, owner, member.name(), member.params())"),
                "optional manifest members must be resolved through optionalMethod");
        assertTrue(source.contains("AVAILABLE = root != null && missing.isEmpty();"),
                "availability must be decided by the required members alone");
    }

    @Test
    void everyTraitReadAnswersEmptyForAVillagerThatIsNotThere() {
        assertTrue(McaCompat.personality(null).isEmpty());
        assertTrue(McaCompat.profession(null).isEmpty());
        assertTrue(McaCompat.ageGroup(null).isEmpty());
        assertFalse(McaCompat.isMcaVillager(null));
    }

    @Test
    void anObserverWithNoResolvableTraitsInterpretsNeutrally() {
        assertSame(VillagerProfileResolver.ObserverTraits.NEUTRAL,
                VillagerProfileResolver.traits(null));
        assertSame(VillagerProfileResolver.ObserverTraits.NEUTRAL,
                VillagerProfileResolver.traits(null, TestFixtures.OVERWORLD_3, TestFixtures.VILLAGER_1));
    }
}
