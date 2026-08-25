package dev.otectus.mcareputation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Resolves repository paths for the tests that read the real source tree and shipped resources off
 * disk rather than through the classpath.
 *
 * <p><b>Why this exists.</b> ModDevGradle's {@code unitTest} runner executes from
 * {@code build/minecraft-junit}, not from the project directory, so every bare relative path that
 * worked under ForgeGradle now resolves to nothing. That is worse than an error: {@code Files.walk}
 * on a missing directory made the boundary tests return early and report <em>green</em>, so
 * {@code OptionalClassloadTest} was silently asserting nothing at all. {@code build.gradle} therefore
 * injects {@code -Dmcareputation.projectRoot}, and everything on-disk goes through here.
 *
 * <p>Resolution deliberately {@link #fail}s rather than skipping. A test that cannot find what it is
 * supposed to inspect has not passed.
 */
public final class TestPaths {

    private static final String PROPERTY = "mcareputation.projectRoot";

    private TestPaths() {
    }

    /** The repository root. */
    public static Path projectRoot() {
        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path root = Paths.get(configured);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        // Fallback for an IDE run that did not pick up the system property: walk up to the first
        // directory holding settings.gradle.
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return fail("cannot locate the project root; expected -D" + PROPERTY
                + " or an ancestor directory containing settings.gradle");
    }

    /** {@code src/main/java/dev/otectus/mcareputation}. */
    public static Path mainSourceRoot() {
        return requireDirectory(projectRoot().resolve("src/main/java/dev/otectus/mcareputation"));
    }

    /** {@code src/main/resources}. */
    public static Path mainResources() {
        return requireDirectory(projectRoot().resolve("src/main/resources"));
    }

    /** {@code build/classes/java/main} — the compiled main classes, for constant-pool scans. */
    public static Path compiledMainClasses() {
        return requireDirectory(projectRoot().resolve("build/classes/java/main"));
    }

    private static Path requireDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            fail("expected a directory at " + path.toAbsolutePath()
                    + "; the test cannot inspect what it cannot find");
        }
        return path;
    }
}
