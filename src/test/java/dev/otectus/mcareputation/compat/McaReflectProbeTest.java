package dev.otectus.mcareputation.compat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays {@link McaReflect#MANIFEST} against every real MCA jar Gradle hands over.
 *
 * <p>The family's other add-ons (Conversations, Quests, Crime) already probe their bindings this way;
 * this closes the same gap here. {@code McaReflect}'s static initialiser only ever sees the one MCA
 * on the dev classpath, so a member MCA renamed in another supported release would surface as an
 * ERROR line on somebody's server. Each jar is opened in its own {@link URLClassLoader}, never on the
 * test classpath, so nothing MCA is linked into the test JVM and the unit suite still runs in the
 * genuine "MCA absent" state.
 *
 * <p>Jar paths arrive in {@code mcareputation.probe.jars} (path-separated), resolved from
 * {@code mca_probe_versions} in gradle.properties by the {@code test} task. Without the property the
 * test is skipped rather than failed, so an IDE run does not turn red for lack of Gradle.
 */
class McaReflectProbeTest {

    private static final String JARS_PROPERTY = "mcareputation.probe.jars";

    @Test
    void manifestResolvesAgainstEveryProbedMcaJar() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No MCA jar to probe (" + JARS_PROPERTY + "); run via Gradle to exercise this.");

        // One loader per jar: detectRoot stops at the first root that resolves, so two MCA builds in
        // one loader would silently exercise whichever root won and leave the other unchecked.
        for (Path jar : jars) {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    new McaHidingLoader(McaReflectProbeTest.class.getClassLoader()))) {
                McaReflect.Resolution resolution = McaReflect.resolveAgainst(loader);

                assertNotNull(resolution.root(),
                        "No supported package root matched " + jar.getFileName() + ". If MCA has moved "
                                + "again, add the new root to McaReflect.SUPPORTED_ROOTS.");
                assertEquals(List.of(), resolution.missing(),
                        jar.getFileName() + " is missing member(s) this mod requires. Either MCA renamed "
                                + "them (update McaReflect.MANIFEST) or removed them (make the member optional "
                                + "and give McaCompat a fallback).");
                System.out.println("[probe] " + jar.getFileName() + " -> " + resolution.root()
                        + (resolution.missingOptional().isEmpty() ? ""
                                : " (optional absent, fallbacks apply: " + resolution.missingOptional() + ")"));
            }
        }
    }

    /** With no MCA anywhere, resolution must report a clean absence rather than throwing. */
    @Test
    void resolutionWithoutMcaIsAbsentAndDoesNotThrow() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            McaReflect.Resolution resolution = McaReflect.resolveAgainst(empty);
            assertNull(resolution.root());
            assertTrue(resolution.missing().isEmpty(),
                    "An absent MCA is not a partial binding; nothing should be reported as a required miss.");
        }
    }

    /** Every manifest member names a class the type list resolves, so a typo cannot pass vacuously. */
    @Test
    void manifestOwnersAreDeclaredTypes() {
        for (McaReflect.Member member : McaReflect.MANIFEST) {
            assertTrue(McaReflect.TYPES.contains(member.owner()),
                    member.key() + " names owner " + member.owner() + " which is not in McaReflect.TYPES");
        }
    }

    /**
     * The test classpath deliberately carries the dev MCA (see {@code McaTraitFallbackTest}), and a
     * {@link URLClassLoader} asks its parent first — so without this every probed jar would resolve
     * against that one MCA and pass vacuously. This parent hides every MCA root so the jar under test
     * is the only place an MCA class can come from, while Minecraft still resolves through it.
     */
    private static final class McaHidingLoader extends ClassLoader {
        private McaHidingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String root : McaReflect.SUPPORTED_ROOTS) {
                if (name.startsWith(root + '.')) {
                    throw new ClassNotFoundException(name + " is hidden from the probe parent loader");
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static List<Path> probeJars() {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty(JARS_PROPERTY, "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Paths.get(entry.trim());
                if (Files.isRegularFile(path)) {
                    jars.add(path);
                }
            }
        }
        return jars;
    }
}
