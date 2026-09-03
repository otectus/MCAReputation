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
    // debug: the diagnostics added with the authority contract and the selection fix
    // ------------------------------------------------------------------

    @Test
    void debugAuthoritiesIsAnOperatorLeaf() {
        assertEquals(List.of("mcareputation", "debug", "authorities"),
                path(assertParses(dispatcher(), "mcareputation debug authorities", source(2))));
    }

    @Test
    void debugStandingDefaultsToTheSourcePlayer() {
        assertEquals(List.of("mcareputation", "debug", "standing"),
                path(assertParses(dispatcher(), "mcareputation debug standing", source(2))));
    }

    @Test
    void debugStandingTakesACommunityOrAPlayerAndBoth() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(List.of("mcareputation", "debug", "standing", "community"),
                path(assertParses(dispatcher, "mcareputation debug standing here", source(2))));
        assertEquals(List.of("mcareputation", "debug", "standing", "player"),
                path(assertParses(dispatcher, "mcareputation debug standing Steve", source(2))),
                "a bare name must reach the player branch, not be swallowed by the community argument");
        assertEquals(List.of("mcareputation", "debug", "standing", "player", "community"),
                path(assertParses(dispatcher,
                        "mcareputation debug standing Steve minecraft:overworld/3", source(2))));
    }

    @Test
    void theDebugBranchIsOperatorOnly() {
        ParseResults<CommandSourceStack> parse =
                dispatcher().parse("mcareputation debug authorities", source(0));
        assertFalse(parse.getExceptions().isEmpty() && !parse.getReader().canRead(),
                "debug is permission level 2; it must not parse for an ordinary player");
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

    @Test
    void titleGrantWithACommunityParses() {
        assertParses(dispatcher(),
                "mcareputation title grant Steve mcaquests:honored_of_village minecraft:overworld/3",
                source(2));
    }
}
