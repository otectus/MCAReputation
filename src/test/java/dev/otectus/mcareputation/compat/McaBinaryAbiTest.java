package dev.otectus.mcareputation.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 14.2: the audited reflection surface, checked against the MCA binary Gradle actually resolved.
 *
 * <p>Reading MCA's source tells you what a commit contained; it does not tell you what the published
 * jar exports after its own build steps. So this test opens {@code mcareputation.mcaJar} — the exact
 * artifact on the compile classpath — in its own {@link URLClassLoader} and resolves every entry of
 * {@link McaReflect#AUDITED_MEMBERS} against it, asserting existence, public accessibility, and the
 * static/instance status the table claims. The table is read from {@code McaReflect} rather than
 * copied, so the thing under test and the thing being audited cannot drift apart.
 *
 * <p>Opt-in by design: {@code test} still runs when the property is absent (an offline checkout, or a
 * contributor without the Conczin Maven), and the check is meaningless without a jar to point at.
 */
@EnabledIfSystemProperty(named = "mcareputation.mcaJar", matches = ".+")
class McaBinaryAbiTest {

    /** The only supported root, so the audited suffixes hang off exactly one prefix. */
    private static final String ROOT = McaReflect.SUPPORTED_ROOTS.get(0);

    private static Path jar() {
        Path jar = Paths.get(System.getProperty("mcareputation.mcaJar"));
        assertTrue(Files.isRegularFile(jar), () -> "mcareputation.mcaJar does not exist: " + jar);
        return jar;
    }

    /**
     * The test classloader is the parent on purpose: the audited signatures name vanilla types
     * ({@code ServerLevel}, {@code BlockPos}), and those must resolve to the same classes the mod
     * compiles against, not to a second copy loaded out of the MCA jar.
     */
    private static URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()},
                McaBinaryAbiTest.class.getClassLoader());
    }

    @Test
    void everyAuditedMemberExistsInTheResolvedMcaJar() throws Exception {
        List<String> problems = new ArrayList<>();
        try (URLClassLoader loader = loader(jar())) {
            for (McaReflect.Member member : McaReflect.AUDITED_MEMBERS) {
                String owner = ROOT + "." + member.ownerSuffix();
                Class<?> type;
                try {
                    // initialize = false: this is an ABI audit, and MCA's static initialisers would
                    // demand a running game. Throwable, because defining the class still loads its
                    // supertypes and a missing one surfaces as an Error, not an Exception.
                    type = Class.forName(owner, false, loader);
                } catch (Throwable t) {
                    problems.add("class " + owner + " missing: " + t);
                    continue;
                }
                Method resolved;
                try {
                    resolved = type.getMethod(member.name(), member.params());
                } catch (Throwable t) {
                    problems.add(member.describe(ROOT) + " missing: " + t);
                    continue;
                }
                if (!Modifier.isPublic(resolved.getModifiers())) {
                    problems.add(member.describe(ROOT) + " is not public");
                }
                if (Modifier.isStatic(resolved.getModifiers()) != member.isStatic()) {
                    problems.add(member.describe(ROOT) + " is "
                            + (member.isStatic() ? "an instance method, but the table says static"
                            : "static, but the table says an instance method"));
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "the audited MCA surface does not match the resolved jar; "
                + "McaReflect would report these as missing at runtime and disable the integration:\n  "
                + String.join("\n  ", problems));
    }

    /**
     * The audit is only worth anything against the jar it was performed on, so the pin is checked by
     * digest, not by file name. Skipped until {@code mca_jar_sha256} is filled in.
     */
    @Test
    void theResolvedJarIsTheOneTheSurfaceWasAuditedAgainst() throws Exception {
        String expected = System.getProperty("mcareputation.mcaJarSha256");
        if (expected == null || expected.isBlank()) {
            return;
        }
        Path jar = jar();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(jar)));
        assertEquals(expected.trim().toLowerCase(java.util.Locale.ROOT), actual,
                () -> "the resolved MCA jar is not the audited artifact: " + jar);
    }
}
