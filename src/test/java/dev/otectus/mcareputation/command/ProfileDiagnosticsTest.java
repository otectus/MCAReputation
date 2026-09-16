package dev.otectus.mcareputation.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §21.1's "use {@code INSPECT} for diagnostics that promise no mutation", checked against the source
 * of the four profile subcommands.
 *
 * <p>Asserted this way because the alternative is asserting nothing. A command handler needs a
 * {@code MinecraftServer}, a level and a {@code CommandSourceStack}, none of which exists in a plain
 * unit test, so the behaviour is covered one layer down by {@code ProfileInspectionTest} — and the
 * thing that test cannot see is whether the commands still call the entry points it exercised. This
 * is the join between the two: the aging entry points are named here, and a handler that starts using
 * one fails this test with the line that did it.
 *
 * <p>{@code debug profilemigration run <budget>} is the one deliberate exception, and it is excluded
 * by name rather than by pattern: an operator asking for an enrichment pass is asking for a mutation,
 * and that branch is separated behind its own literal for exactly that reason.
 */
class ProfileDiagnosticsTest {

    /**
     * Calls that may age a record. {@code getProfileDetailed} is the live read the standing screen
     * takes — correct there, wrong in a diagnostic, and a single character away from
     * {@code inspectStoredProfile}.
     */
    private static final List<String> AGING_CALLS = List.of(
            "getProfileDetailed(",
            "Intent.QUERY",
            "Intent.MUTATE",
            "ReconciliationService.reconcile(",
            "recomputeScore(",
            "bumpProfileRevision(",
            "reconcileProfile(",
            "settleProfileEvidence(",
            "creditTrackers().consume(",
            "dropExpired(");

    /** The four handlers §21.1 adds, by the method name each debug child is wired to. */
    private static final List<String> HANDLERS = List.of(
            "debugProfile", "debugCredit", "debugProfileIncident", "debugProfileMigration");

    private static String source() throws IOException {
        Path file = Paths.get(
                "src/main/java/dev/otectus/mcareputation/command/ReputationCommand.java");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The body of one method, from its signature to the next method at the same indentation. */
    private static String body(String source, String handler) {
        int start = source.indexOf("    private static int " + handler + "(");
        assertTrue(start >= 0, handler + " is not declared in ReputationCommand");
        // Up to the next declaration at the same indentation. That includes the following method's
        // javadoc, which is harmless: none of the names below appears in prose here, and a scan that
        // tried to strip comments would be a Java parser with a test's error messages.
        int end = source.indexOf("\n    private static ", start + 1);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    @Test
    void noProfileDiagnosticTakesAnAgingRead() throws IOException {
        String source = source();
        List<String> offenders = new ArrayList<>();
        for (String handler : HANDLERS) {
            String body = body(source, handler);
            for (String call : AGING_CALLS) {
                if (body.contains(call)) {
                    offenders.add(handler + " calls " + call);
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "a diagnostic that promises no mutation must not take a "
                + "read that can age a record (§21.1):\n  " + String.join("\n  ", offenders));
    }

    /** And the profile read that is taken is explicitly the inspecting one. */
    @Test
    void theProfileReadIsTheInspectingEntryPoint() throws IOException {
        assertTrue(body(source(), "debugProfile").contains("inspectStoredProfile("),
                "debug profile must read through McaReputationApi.inspectStoredProfile, which enters "
                        + "the gate with INSPECT");
    }

    /**
     * The one mutating branch is reachable only through {@code run <budget>}, so the bare
     * {@code profilemigration} cannot advance anything.
     */
    @Test
    void theMigrationPassRunsOnlyWhenABudgetWasAskedFor() throws IOException {
        String body = body(source(), "debugProfileMigration");
        assertTrue(body.contains("if (budget > 0) {"),
                "the enrichment pass is guarded by an explicitly requested budget");
        int guard = body.indexOf("if (budget > 0) {");
        int call = body.indexOf("advanceProfileMigration(");
        assertTrue(call > guard, "and the call sits inside that guard, not before it");
    }
}
