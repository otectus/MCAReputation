package dev.otectus.mcareputation.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /mcareputation tree, parsed for real with Brigadier and no game running (spec §24).
 *
 * <p>The regressions this guards: the alias redirecting to an orphan node (double registration), the
 * one-arg operator form {@code get <player>} being unparseable because a string-typed community
 * argument swallowed the name, community keys like {@code minecraft:overworld/3} being unparseable
 * at all as unquoted Brigadier strings, and the self forms of {@code history}/{@code incident list}
 * lacking community and limit arguments.
 */
class CommandTreeTest {

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ReputationCommand.register(dispatcher);
        return dispatcher;
    }

    private static CommandSourceStack source(int permissionLevel) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permissionLevel,
                "test", Component.literal("test"), null, null);
    }

    /** The node names along the parsed path, e.g. [mcareputation, get, player]. */
    private static List<String> path(ParseResults<CommandSourceStack> parse) {
        return parse.getContext().getNodes().stream().map(node -> node.getNode().getName()).toList();
    }

    private static ParseResults<CommandSourceStack> assertParses(
            CommandDispatcher<CommandSourceStack> dispatcher, String input, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(input, source);
        assertTrue(parse.getExceptions().isEmpty(), () -> input + " -> " + parse.getExceptions());
        assertFalse(parse.getReader().canRead(), () -> input + " left unparsed input");
        assertNotNull(parse.getContext().getNodes().get(parse.getContext().getNodes().size() - 1)
                .getNode().getCommand(), () -> input + " parsed to a node with no command");
        return parse;
    }

    // ------------------------------------------------------------------
    // Registration and the alias
    // ------------------------------------------------------------------

    @Test
    void theAliasRedirectsToTheRegisteredRootNotAnOrphan() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("mcareputation");
        CommandNode<CommandSourceStack> alias = dispatcher.getRoot().getChild("mcarep");
        assertNotNull(root);
        assertNotNull(alias);
        assertSame(root, alias.getRedirect(),
                "a redirect at an unregistered orphan is dropped when the tree is sent to clients");
        assertNotNull(root.getCommand(), "bare /mcareputation prints usage");
        assertNotNull(alias.getCommand(), "bare /mcarep prints usage");
    }

    @Test
    void theAliasParsesSubcommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertParses(dispatcher, "mcarep get here", source(0));
    }

    // ------------------------------------------------------------------
    // get: community/player disambiguation (§24)
    // ------------------------------------------------------------------

    @Test
    void getWithAPlayerNameReachesThePlayerBranchForOperators() {
        ParseResults<CommandSourceStack> parse =
                assertParses(dispatcher(), "mcareputation get Steve", source(2));
        assertEquals(List.of("mcareputation", "get", "player"), path(parse),
                "the one-argument operator form was unreachable before the community argument "
                        + "learned to reject names at parse time");
    }

    @Test
    void getWithACommunityKeyReachesTheCommunityBranch() {
        ParseResults<CommandSourceStack> parse =
                assertParses(dispatcher(), "mcareputation get minecraft:overworld/3", source(0));
        assertEquals(List.of("mcareputation", "get", "community"), path(parse),
                "an unquoted dimension/villageId was previously unparseable as a Brigadier string");
    }

    @Test
    void getHereIsTheCommunityBranchEvenForOperators() {
        ParseResults<CommandSourceStack> parse =
                assertParses(dispatcher(), "mcareputation get here", source(2));
        assertEquals(List.of("mcareputation", "get", "community"), path(parse));
    }

    @Test
    void getPlayerThenCommunityParsesBothArguments() {
        ParseResults<CommandSourceStack> parse =
                assertParses(dispatcher(), "mcareputation get Steve minecraft:overworld/3", source(2));
        assertEquals(List.of("mcareputation", "get", "player", "community"), path(parse));
    }

    @Test
    void aNonOperatorAskingAboutAnotherPlayerGetsAnError() {
        ParseResults<CommandSourceStack> parse = dispatcher().parse("mcareputation get Steve", source(0));
        assertFalse(parse.getExceptions().isEmpty() && !parse.getReader().canRead(),
                "with no permitted player branch, a bare name must not silently parse as something else");
    }

    // ------------------------------------------------------------------
    // Self forms of history and incident list (§24: the player is optional)
    // ------------------------------------------------------------------

    @Test
    void selfHistoryTakesCommunityAndLimit() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "history", "community"),
                path(assertParses(dispatcher, "mcareputation history minecraft:overworld/3", source(0))));
        assertEquals(List.of("mcareputation", "history", "community", "limit"),
                path(assertParses(dispatcher, "mcareputation history here 25", source(0))));
    }

    @Test
    void operatorHistoryStillTakesPlayerCommunityAndLimit() {
        assertEquals(List.of("mcareputation", "history", "player", "community", "limit"),
                path(assertParses(dispatcher(),
                        "mcareputation history Steve minecraft:overworld/3 5", source(2))));
    }

    @Test
    void selfIncidentListTakesACommunity() {
        assertEquals(List.of("mcareputation", "incident", "list", "community"),
                path(assertParses(dispatcher(),
                        "mcareputation incident list minecraft:the_nether/3", source(0))));
    }

    // ------------------------------------------------------------------
    // Titles: the global literal is its own branch
    // ------------------------------------------------------------------

    @Test
    void titleGrantGlobalIsItsOwnExecutableBranch() {
        ParseResults<CommandSourceStack> parse = assertParses(dispatcher(),
                "mcareputation title grant Steve mcareputation:wanderer global", source(2));
        assertEquals("global", path(parse).get(path(parse).size() - 1));
    }

    // ------------------------------------------------------------------
    // Admin tooling (§24)
    // ------------------------------------------------------------------

    @Test
    void exportParsesBareAndWithAPlayer() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "export"),
                path(assertParses(dispatcher, "mcareputation export", source(3))));
        assertEquals(List.of("mcareputation", "export", "player"),
                path(assertParses(dispatcher, "mcareputation export Steve", source(3))));
    }

    /** The whole store in one file is an operator's document, not a player's. */
    @Test
    void aPlayerCannotReachExport() {
        assertNull(dispatcher().parse("mcareputation export", source(0)).getContext().getNodes()
                .stream().map(node -> node.getNode().getName())
                .filter("export"::equals).findFirst().orElse(null),
                "export must not be reachable below permission 3");
    }

    @Test
    void topParsesWithAndWithoutALimit() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "top", "community"),
                path(assertParses(dispatcher, "mcareputation top here", source(2))));
        assertEquals(List.of("mcareputation", "top", "community", "limit"),
                path(assertParses(dispatcher, "mcareputation top minecraft:overworld/3 5", source(2))));
    }

    @Test
    void communityDecaySwitchesAndStatusParse() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "community", "community", "decay", "on"),
                path(assertParses(dispatcher, "mcareputation community here decay on", source(3))));
        assertEquals(List.of("mcareputation", "community", "community", "decay", "status"),
                path(assertParses(dispatcher, "mcareputation community here decay status", source(2))));
    }

    // ------------------------------------------------------------------
    // Diagnostics (§11): all under debug, all at permission 2
    // ------------------------------------------------------------------

    @Test
    void theNewDiagnosticsParseUnderDebug() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "debug", "quarantine"),
                path(assertParses(dispatcher, "mcareputation debug quarantine", source(2))));
        assertEquals(List.of("mcareputation", "debug", "receipts", "player"),
                path(assertParses(dispatcher, "mcareputation debug receipts Steve", source(2))));
        assertEquals(List.of("mcareputation", "debug", "receipts", "player", "community"),
                path(assertParses(dispatcher,
                        "mcareputation debug receipts Steve minecraft:overworld/3", source(2))));
        assertEquals(List.of("mcareputation", "debug", "supersede", "player", "community"),
                path(assertParses(dispatcher,
                        "mcareputation debug supersede Steve minecraft:overworld/3", source(2))));
    }

    /** The community is not optional for supersede: "which ledger" has no sensible default here. */
    @Test
    void supersedeWithoutACommunityIsNotExecutable() {
        ParseResults<CommandSourceStack> parse =
                dispatcher().parse("mcareputation debug supersede Steve", source(2));
        assertNull(parse.getContext().getNodes().get(parse.getContext().getNodes().size() - 1)
                .getNode().getCommand());
    }

    @Test
    void debugStandingStillTakesAPlayerAndACommunity() {
        assertEquals(List.of("mcareputation", "debug", "standing", "player", "community"),
                path(assertParses(dispatcher(),
                        "mcareputation debug standing Steve minecraft:overworld/3", source(2))));
    }

    /** Every diagnostic sits behind the one permission gate on the debug group. */
    @Test
    void aPlayerCannotReachTheDiagnostics() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        for (String input : List.of("mcareputation debug quarantine",
                "mcareputation debug receipts Steve",
                "mcareputation debug supersede Steve minecraft:overworld/3")) {
            assertNull(dispatcher.parse(input, source(1)).getContext().getNodes().stream()
                    .map(node -> node.getNode().getName())
                    .filter("debug"::equals).findFirst().orElse(null),
                    input + " must not be reachable below permission 2");
        }
    }

    @Test
    void titleGrantWithACommunityParses() {
        assertParses(dispatcher(),
                "mcareputation title grant Steve mcaquests:honored_of_village minecraft:overworld/3",
                source(2));
    }
}
