package dev.otectus.mcareputation.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two-way parity between {@code en_us.json} and the code that references it (§21.3, §28.4).
 *
 * <p>{@code ContentValidationTest} covers the datapack side — incident displays, gossip phrases,
 * tier names and descriptions. This test covers the <b>Java</b> side by scanning the main source for
 * literal {@code Component.translatable} keys, in both directions: every referenced key must exist,
 * and every shipped key must be referenced by something (source, shipped JSON, or one of the dynamic
 * key families built by concatenation). A key that fails the second check is dead weight that quietly
 * rots — exactly how nine tier descriptions shipped unrendered for a whole version.
 */
class LangParityTest {

    private static final Pattern TRANSLATABLE =
            Pattern.compile("Component\\.translatable\\(\\s*\"(mcareputation\\.[a-zA-Z0-9_.]+)\"");

    /** Families assembled at runtime by concatenation; their members are checked by other tests. */
    private static final List<String> DYNAMIC_PREFIXES = List.of(
            "mcareputation.status.",
            "mcareputation.age.",
            "mcareputation.tier.",
            "mcareputation.incident.",
            "mcareputation.gossip.");

    /** Keys referenced outside Component.translatable literals (keybinds, tests, MCA hooks). */
    private static final Set<String> KNOWN_INDIRECT = Set.of(
            "key.mcareputation.open",
            "key.categories.mcareputation");

    private static Path projectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java/dev/otectus/mcareputation"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("could not locate the project root from " + Paths.get("").toAbsolutePath());
    }

    private static JsonObject lang(Path root) throws IOException {
        return JsonParser.parseString(Files.readString(
                        root.resolve("src/main/resources/assets/mcareputation/lang/en_us.json")))
                .getAsJsonObject();
    }

    /** Any {@code "mcareputation.…"} string literal anywhere in main source. */
    private static final Pattern ANY_KEY_LITERAL =
            Pattern.compile("\"(mcareputation\\.[a-zA-Z0-9_.]+)\"");

    private static Set<String> keysMatching(Path root, Pattern pattern) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith(".")) { // a trailing dot is a dynamic-prefix construction site
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private static String resourceJsonBlob(Path root) throws IOException {
        StringBuilder blob = new StringBuilder();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/resources/data"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                blob.append(Files.readString(file)).append('\n');
            }
        }
        return blob.toString();
    }

    @Test
    void everyKeyTheCodeReferencesExists() throws IOException {
        Path root = projectRoot();
        JsonObject lang = lang(root);
        List<String> missing = new ArrayList<>();
        for (String key : keysMatching(root, TRANSLATABLE)) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), () -> "keys referenced in Java but absent from en_us.json:\n  "
                + String.join("\n  ", missing));
    }

    @Test
    void everyShippedKeyIsReferencedBySomething() throws IOException {
        Path root = projectRoot();
        JsonObject lang = lang(root);
        // The broad literal scan, not just direct translatable() calls: keys selected by a ternary or
        // stored in a variable before translation still count as referenced.
        Set<String> inSource = keysMatching(root, ANY_KEY_LITERAL);
        String resourceBlob = resourceJsonBlob(root);

        List<String> orphans = new ArrayList<>();
        for (String key : lang.keySet()) {
            if (key.startsWith("_comment")) {
                continue;
            }
            boolean referenced = inSource.contains(key)
                    || KNOWN_INDIRECT.contains(key)
                    || resourceBlob.contains("\"" + key + "\"")
                    || DYNAMIC_PREFIXES.stream().anyMatch(key::startsWith);
            if (!referenced) {
                orphans.add(key);
            }
        }
        assertTrue(orphans.isEmpty(), () -> "keys shipped in en_us.json that nothing references:\n  "
                + String.join("\n  ", orphans));
    }
}
