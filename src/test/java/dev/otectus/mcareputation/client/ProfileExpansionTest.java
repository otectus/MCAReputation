package dev.otectus.mcareputation.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the profile expansion's open/closed state lives, and when it is reset (§18.1).
 *
 * <p>§18.1 asks for one compact expansion rather than a dossier, so the pane starts collapsed every
 * time the screen is opened — but it has to survive everything that rebuilds the screen without
 * reopening it (a page turn, a snapshot refresh, a window resize), which is why the flag is a static
 * on {@link ClientReputationData} and not a screen field. Those two requirements pull in opposite
 * directions, and for one release the flag only ever reset on logout: closing and reopening the
 * screen came back expanded.
 *
 * <p>The flag itself is asserted directly. <b>Where</b> the reset is called from cannot be: building
 * a {@code Screen} needs a running client, so that half is asserted against the source, because the
 * distinction between the constructor (once per open) and {@code init()} (once per widget rebuild) is
 * the whole fix.
 */
class ProfileExpansionTest {

    private static final Path SCREEN_SOURCE =
            Paths.get("src/main/java/dev/otectus/mcareputation/client/ReputationScreen.java");

    @AfterEach
    void collapse() {
        // Process-wide state: leave it as a fresh screen would find it.
        ClientReputationData.collapseProfile();
    }

    @Test
    void togglingOpensAndClosesTheExpansion() {
        ClientReputationData.collapseProfile();
        assertFalse(ClientReputationData.profileExpanded(), "a collapsed pane starts closed");

        ClientReputationData.toggleProfileExpanded();
        assertTrue(ClientReputationData.profileExpanded(), "the toggle opens the pane");

        ClientReputationData.toggleProfileExpanded();
        assertFalse(ClientReputationData.profileExpanded(), "the toggle closes it again");
    }

    @Test
    void collapsingAnOpenExpansionClosesItAndIsIdempotent() {
        ClientReputationData.toggleProfileExpanded();
        assertTrue(ClientReputationData.profileExpanded());

        ClientReputationData.collapseProfile();
        assertFalse(ClientReputationData.profileExpanded(), "opening a screen collapses the pane");

        ClientReputationData.collapseProfile();
        assertFalse(ClientReputationData.profileExpanded(), "a second open stays collapsed");
    }

    @Test
    void clearingTheCacheCollapsesTheExpansion() {
        ClientReputationData.toggleProfileExpanded();
        ClientReputationData.clear();
        assertFalse(ClientReputationData.profileExpanded(),
                "logging out must not carry an open pane into the next world");
    }

    /**
     * The reset belongs in the constructor, which runs once per open — not in {@code init()}, which
     * re-runs on every {@code rebuildWidgets()} and on a window resize and would collapse the pane
     * the player just opened.
     */
    @Test
    void theScreenCollapsesTheExpansionOnOpenAndNotOnEveryRebuild() throws IOException {
        if (!Files.isReadable(SCREEN_SOURCE)) {
            return; // running from a packaged artifact rather than the source tree
        }
        String source = Files.readString(SCREEN_SOURCE, StandardCharsets.UTF_8);

        String constructor = bodyOf(source, "public ReputationScreen(@Nullable Screen parent) {");
        assertTrue(constructor.contains("ClientReputationData.collapseProfile();"),
                "the screen's constructor must collapse the profile expansion on every open");

        String init = bodyOf(source, "protected void init() {");
        assertFalse(init.contains("collapseProfile"),
                "init() re-runs on a rebuild and a resize, so it must not collapse the expansion");
    }

    /** The text of a method body, from its declaration to the closing brace at member indent. */
    private static String bodyOf(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, () -> "declaration not found in ReputationScreen: " + declaration);
        start += declaration.length();
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, () -> "unterminated method body for: " + declaration);
        return source.substring(start, end);
    }
}
