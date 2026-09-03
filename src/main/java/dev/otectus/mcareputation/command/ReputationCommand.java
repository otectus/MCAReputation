package dev.otectus.mcareputation.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityResolver;
import dev.otectus.mcareputation.compat.McaReflect;
import dev.otectus.mcareputation.data.ReputationContentValidator;
import dev.otectus.mcareputation.event.CoreIncidentAuthorities;
import dev.otectus.mcareputation.event.LegacyImportProviders;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.network.SnapshotSelection;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.ReputationService;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleService;
import dev.otectus.mcareputation.reputation.Titles;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code /mcareputation} command tree (spec §24).
 *
 * <h2>Permissions</h2>
 *
 * <p>Self-queries need no elevated permission — a player asking about their own standing is exactly
 * what the mod is for. Everything that reads <em>another</em> player's state or mutates anything
 * requires level 2, and every mutation writes an audit line naming the executor, the target, the
 * community, the old and new score, and the reason (§24).
 *
 * <h2>Community arguments</h2>
 *
 * <p>Spelled {@code <dimension>/<villageId>} — for example {@code minecraft:overworld/3} — or the
 * literal {@code here}, which resolves the nearest community to the executor server-side. Suggestions
 * are drawn from the communities the store already knows, so the usual case is a tab-complete rather
 * than typing a dimension by hand. A bare integer is deliberately <b>not</b> accepted: village ids are
 * per-dimension and an unqualified one is ambiguous (§12.1).
 */
public final class ReputationCommand {

    private static final SimpleCommandExceptionType NO_COMMUNITY = new SimpleCommandExceptionType(
            Component.translatable("mcareputation.command.error.no_community"));
    private static final SimpleCommandExceptionType BAD_COMMUNITY = new SimpleCommandExceptionType(
            Component.translatable("mcareputation.command.error.bad_community"));
    private static final SimpleCommandExceptionType NO_RECORD = new SimpleCommandExceptionType(
            Component.translatable("mcareputation.command.error.no_record"));
    private static final SimpleCommandExceptionType NO_INCIDENT = new SimpleCommandExceptionType(
            Component.translatable("mcareputation.command.error.no_incident"));

    private static final String HERE = "here";

    /** Suggests every community the store knows about, plus {@code here}. */
    private static final SuggestionProvider<CommandSourceStack> COMMUNITY_SUGGESTIONS =
            (context, builder) -> {
                List<String> options = new java.util.ArrayList<>();
                options.add(HERE);
                ReputationSavedData.get(context.getSource().getServer()).allKnownCommunities()
                        .forEach(key -> options.add(key.asString()));
                return SharedSuggestionProvider.suggest(options, builder);
            };

    private static final SuggestionProvider<CommandSourceStack> INCIDENT_TYPE_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(IncidentRegistry.ids(), builder);

    private static final SuggestionProvider<CommandSourceStack> TITLE_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(Titles.ids(), builder);

    private static final SuggestionProvider<CommandSourceStack> STATUS_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(IncidentStatus.values()).map(IncidentStatus::jsonName).toList(),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> LADDER_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(
                    ReputationTiers.allWithFallback().keySet(), builder);

    private ReputationCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mcareputation")
                .executes(ReputationCommand::usage)
                .then(buildGet())
                .then(buildList())
                .then(buildHistory())
                .then(buildAdd())
                .then(buildSet())
                .then(buildIncident())
                .then(buildTitle())
                .then(buildTiers())
                .then(buildValidate())
                .then(buildReload())
                .then(buildMigrate())
                .then(buildDebug());
        // Register exactly once and redirect the alias to the *registered* node. Registering the same
        // builder twice merges a second copy of the tree into the dispatcher and returns an orphan
        // node — a redirect aimed at an orphan is dropped when the usable-command tree is sent, which
        // left /mcarep bare and completion-less on every client.
        var registered = dispatcher.register(root);
        dispatcher.register(Commands.literal("mcarep")
                .executes(ReputationCommand::usage)
                .redirect(registered));
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.usage"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildGet() {
        // The community branch is registered first and the two disambiguate at parse time:
        // CommunityArgument rejects anything that is not "here" or dimension/villageId, so "Steve"
        // falls through to the player branch while "minecraft:overworld/3" (which a player-name
        // parser cannot consume) lands here. Registering the player branch first instead would
        // swallow the literal "here" for operators, since it parses as a plausible player name.
        return Commands.literal("get")
                .executes(ctx -> get(ctx, self(ctx), resolveCommunity(ctx, null)))
                .then(Commands.argument("community", CommunityArgument.community())
                        .suggests(COMMUNITY_SUGGESTIONS)
                        .executes(ctx -> get(ctx, self(ctx),
                                resolveCommunity(ctx, CommunityArgument.getCommunity(ctx, "community")))))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> get(ctx, EntityArgument.getPlayer(ctx, "player"),
                                resolveCommunity(ctx, null)))
                        .then(Commands.argument("community", CommunityArgument.community())
                                .suggests(COMMUNITY_SUGGESTIONS)
                                .executes(ctx -> get(ctx, EntityArgument.getPlayer(ctx, "player"),
                                        resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community"))))));
    }

    private static int get(CommandContext<CommandSourceStack> ctx, ServerPlayer target, CommunityKey community)
            throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();
        var snapshot = ReputationService.snapshot(server, target.getUUID(), community, gameTime);
        if (snapshot.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.get.none",
                    target.getDisplayName(), Component.literal(community.asString())), false);
            return 0;
        }
        var value = snapshot.get();
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.get",
                target.getDisplayName(),
                value.metadata().displayName(community),
                value.score(),
                value.tier().name(),
                value.totalIncidentCount()), false);
        return value.score();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildList() {
        return Commands.literal("list")
                .executes(ctx -> list(ctx, self(ctx)))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> list(ctx, EntityArgument.getPlayer(ctx, "player"))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();
        var snapshots = ReputationService.knownCommunities(server, target.getUUID(), gameTime);
        if (snapshots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.list.empty",
                    target.getDisplayName()), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.list.header",
                target.getDisplayName(), snapshots.size()), false);
        snapshots.forEach(snapshot -> ctx.getSource().sendSuccess(() ->
                Component.literal(" • ")
                        .append(snapshot.metadata().displayName(snapshot.community()))
                        .append(Component.literal(" (" + snapshot.community().asString() + "): "))
                        .append(Component.literal(Integer.toString(snapshot.score())))
                        .append(Component.literal(" — "))
                        .append(snapshot.tier().name()), false));
        return snapshots.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildHistory() {
        // §24: the player is optional. A player may read their own history in any community they
        // choose, with a limit — not only the defaults — so the community/limit chain hangs off the
        // self branch as well as the operator's player branch. Disambiguation is the same parse-time
        // rule as in buildGet.
        return Commands.literal("history")
                .executes(ctx -> history(ctx, self(ctx), resolveCommunity(ctx, null), 10))
                .then(Commands.argument("community", CommunityArgument.community())
                        .suggests(COMMUNITY_SUGGESTIONS)
                        .executes(ctx -> history(ctx, self(ctx),
                                resolveCommunity(ctx, CommunityArgument.getCommunity(ctx, "community")),
                                10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1,
                                        ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY))
                                .executes(ctx -> history(ctx, self(ctx),
                                        resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community")),
                                        IntegerArgumentType.getInteger(ctx, "limit")))))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> history(ctx, EntityArgument.getPlayer(ctx, "player"),
                                resolveCommunity(ctx, null), 10))
                        .then(Commands.argument("community", CommunityArgument.community())
                                .suggests(COMMUNITY_SUGGESTIONS)
                                .executes(ctx -> history(ctx, EntityArgument.getPlayer(ctx, "player"),
                                        resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community")),
                                        10))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1,
                                                ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY))
                                        .executes(ctx -> history(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                resolveCommunity(ctx,
                                                        CommunityArgument.getCommunity(ctx, "community")),
                                                IntegerArgumentType.getInteger(ctx, "limit"))))));
    }

    private static int history(CommandContext<CommandSourceStack> ctx, ServerPlayer target,
                               CommunityKey community, int limit) {
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();
        List<ReputationIncidentView> incidents =
                ReputationService.recentIncidents(server, target.getUUID(), community, limit, gameTime);
        if (incidents.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.history.empty",
                    target.getDisplayName(), Component.literal(community.asString())), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.history.header",
                target.getDisplayName(), Component.literal(community.asString()), incidents.size()), false);
        for (ReputationIncidentView view : incidents) {
            ctx.getSource().sendSuccess(() -> Component.literal(" • ")
                    .append(view.display())
                    .append(Component.literal(" [" + view.type() + " " + view.status().jsonName()
                            + " " + (view.currentContribution() >= 0 ? "+" : "")
                            + view.currentContribution()
                            + (view.pinned() ? " pinned" : "") + "] ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(view.id().toString().substring(0, 8))
                            .withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        return incidents.size();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildAdd() {
        return Commands.literal("add")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(
                                        -ReputationBounds.ABSOLUTE_SCORE_LIMIT,
                                        ReputationBounds.ABSOLUTE_SCORE_LIMIT))
                                .executes(ctx -> adjust(ctx, false, resolveCommunity(ctx, null), ""))
                                .then(Commands.argument("community", CommunityArgument.community())
                                        .suggests(COMMUNITY_SUGGESTIONS)
                                        .executes(ctx -> adjust(ctx, false, resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community")), ""))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> adjust(ctx, false, resolveCommunity(ctx,
                                                                CommunityArgument.getCommunity(ctx, "community")),
                                                        StringArgumentType.getString(ctx, "reason")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSet() {
        return Commands.literal("set")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(
                                        -ReputationBounds.ABSOLUTE_SCORE_LIMIT,
                                        ReputationBounds.ABSOLUTE_SCORE_LIMIT))
                                .executes(ctx -> adjust(ctx, true, resolveCommunity(ctx, null), ""))
                                .then(Commands.argument("community", CommunityArgument.community())
                                        .suggests(COMMUNITY_SUGGESTIONS)
                                        .executes(ctx -> adjust(ctx, true, resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community")), ""))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> adjust(ctx, true, resolveCommunity(ctx,
                                                                CommunityArgument.getCommunity(ctx, "community")),
                                                        StringArgumentType.getString(ctx, "reason")))))));
    }

    private static int adjust(CommandContext<CommandSourceStack> ctx, boolean absolute,
                              CommunityKey community, String reason) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();

        ReputationResult result = absolute
                ? ReputationService.setScore(server, target.getUUID(), community, amount,
                        BuiltinIncidents.SOURCE_COMMAND, gameTime)
                : ReputationService.addScore(server, target.getUUID(), community, amount,
                        BuiltinIncidents.SOURCE_COMMAND, gameTime);

        // §24: every mutating command produces an audit line with executor, target, community,
        // old/new score, and reason. This is the record an administrator needs when a player asks
        // why their standing changed without them doing anything.
        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} ran /mcareputation {} on {} in {}: {} -> {}{}",
                ctx.getSource().getTextName(), absolute ? "set" : "add",
                target.getGameProfile().getName(), community.asString(),
                result.oldScore(), result.newScore(),
                reason.isBlank() ? "" : " (reason: " + reason + ")");

        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.adjust",
                target.getDisplayName(), Component.literal(community.asString()),
                result.oldScore(), result.newScore()), true);
        return result.newScore();
    }

    // ------------------------------------------------------------------
    // Incidents
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildIncident() {
        return Commands.literal("incident")
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                        .suggests(INCIDENT_TYPE_SUGGESTIONS)
                                        .executes(ctx -> incidentAdd(ctx, resolveCommunity(ctx, null)))
                                        .then(Commands.argument("community", CommunityArgument.community())
                                                .suggests(COMMUNITY_SUGGESTIONS)
                                                .executes(ctx -> incidentAdd(ctx, resolveCommunity(ctx,
                                                        CommunityArgument.getCommunity(ctx, "community"))))))))
                .then(Commands.literal("list")
                        .executes(ctx -> history(ctx, self(ctx), resolveCommunity(ctx, null), 20))
                        .then(Commands.argument("community", CommunityArgument.community())
                                .suggests(COMMUNITY_SUGGESTIONS)
                                .executes(ctx -> history(ctx, self(ctx),
                                        resolveCommunity(ctx,
                                                CommunityArgument.getCommunity(ctx, "community")),
                                        20)))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> history(ctx, EntityArgument.getPlayer(ctx, "player"),
                                        resolveCommunity(ctx, null), 20))
                                .then(Commands.argument("community", CommunityArgument.community())
                                        .suggests(COMMUNITY_SUGGESTIONS)
                                        .executes(ctx -> history(ctx,
                                                EntityArgument.getPlayer(ctx, "player"),
                                                resolveCommunity(ctx,
                                                        CommunityArgument.getCommunity(ctx, "community")),
                                                20)))))
                .then(Commands.literal("resolve")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("incident", UuidArgument.uuid())
                                        .then(Commands.argument("status", StringArgumentType.word())
                                                .suggests(STATUS_SUGGESTIONS)
                                                .executes(ReputationCommand::incidentResolve)))))
                .then(Commands.literal("pin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("incident", UuidArgument.uuid())
                                        .then(Commands.argument("pinned", BoolArgumentType.bool())
                                                .executes(ReputationCommand::incidentPin)))));
    }

    private static int incidentAdd(CommandContext<CommandSourceStack> ctx, CommunityKey community)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ResourceLocation type = ResourceLocationArgument.getId(ctx, "type");
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();

        ReputationResult result = ReputationService.record(ReputationRequest
                .builder(server, target.getUUID(), community, type, BuiltinIncidents.SOURCE_COMMAND)
                // A distinct key per invocation: an administrator adding the same incident twice on
                // purpose should get two records, unlike an integration replaying an outcome.
                .dedupeKey("command:" + UUID.randomUUID())
                .gameTime(gameTime)
                .build());

        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} added incident {} to {} in {}: {} -> {}",
                ctx.getSource().getTextName(), type, target.getGameProfile().getName(),
                community.asString(), result.oldScore(), result.newScore());

        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.translatable("mcareputation.command.incident.refused",
                    Component.literal(result.reason().name().toLowerCase(java.util.Locale.ROOT))));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.incident.added",
                Component.literal(type.toString()), target.getDisplayName(),
                result.oldScore(), result.newScore()), true);
        return 1;
    }

    private static int incidentResolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        UUID incidentId = UuidArgument.getUuid(ctx, "incident");
        String rawStatus = StringArgumentType.getString(ctx, "status");
        IncidentStatus status = IncidentStatus.byName(rawStatus).orElse(null);
        if (status == null) {
            ctx.getSource().sendFailure(Component.translatable("mcareputation.command.error.bad_status",
                    Component.literal(rawStatus)));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        long gameTime = server.overworld().getGameTime();

        // The incident id alone does not say which community it belongs to; find it.
        Optional<CommunityKey> owner = findIncidentCommunity(server, target.getUUID(), incidentId);
        if (owner.isEmpty()) {
            throw NO_INCIDENT.create();
        }
        ResolutionResult result = ReputationService.resolve(server, target.getUUID(), owner.get(),
                incidentId, status, BuiltinIncidents.SOURCE_COMMAND, gameTime);

        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} resolved incident {} of {} to {}: {} -> {}",
                ctx.getSource().getTextName(), incidentId, target.getGameProfile().getName(),
                status.jsonName(), result.oldScore(), result.newScore());

        if (!result.applied()) {
            ctx.getSource().sendFailure(Component.translatable("mcareputation.command.incident.refused",
                    Component.literal(result.reason().name().toLowerCase(java.util.Locale.ROOT))));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.incident.resolved",
                Component.literal(status.jsonName()), target.getDisplayName(),
                result.oldScore(), result.newScore()), true);
        return 1;
    }

    private static int incidentPin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        UUID incidentId = UuidArgument.getUuid(ctx, "incident");
        boolean pinned = BoolArgumentType.getBool(ctx, "pinned");
        MinecraftServer server = ctx.getSource().getServer();

        Optional<CommunityKey> owner = findIncidentCommunity(server, target.getUUID(), incidentId);
        if (owner.isEmpty()) {
            throw NO_INCIDENT.create();
        }
        Optional<IncidentRecord> incident =
                ReputationService.incident(server, target.getUUID(), owner.get(), incidentId);
        if (incident.isEmpty()) {
            throw NO_INCIDENT.create();
        }
        incident.get().setPinned(pinned);
        ReputationSavedData.get(server).setDirty();

        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} set pinned={} on incident {} of {}",
                ctx.getSource().getTextName(), pinned, incidentId, target.getGameProfile().getName());
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.incident.pinned",
                Component.literal(incidentId.toString().substring(0, 8)), pinned), true);
        return 1;
    }

    private static Optional<CommunityKey> findIncidentCommunity(MinecraftServer server, UUID playerId,
                                                                UUID incidentId) {
        return ReputationSavedData.get(server).player(playerId)
                .flatMap(record -> record.communities().stream()
                        .filter(community -> community.incident(incidentId).isPresent())
                        .map(CommunityReputationRecord::key)
                        .findFirst());
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildTitle() {
        // The "global" literal has its own handler on purpose. Routing it through the scope-derived
        // grant would consult Titles.scopeOf, which defaults an undefined title to VILLAGE — and with
        // no community the grant silently did nothing while reporting "unchanged". Saying "global"
        // must mean global, defined title or not. The bare form resolves "here", like every other
        // community-optional subcommand.
        return Commands.literal("title")
                .then(Commands.literal("grant")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(TITLE_SUGGESTIONS)
                                        .executes(ctx -> title(ctx, true, resolveCommunity(ctx, null)))
                                        .then(Commands.literal("global")
                                                .executes(ctx -> titleGlobal(ctx, true)))
                                        .then(Commands.argument("community", CommunityArgument.community())
                                                .suggests(COMMUNITY_SUGGESTIONS)
                                                .executes(ctx -> title(ctx, true, resolveCommunity(ctx,
                                                        CommunityArgument.getCommunity(ctx, "community"))))))))
                .then(Commands.literal("revoke")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(TITLE_SUGGESTIONS)
                                        .executes(ctx -> title(ctx, false, resolveCommunity(ctx, null)))
                                        .then(Commands.literal("global")
                                                .executes(ctx -> titleGlobal(ctx, false)))
                                        .then(Commands.argument("community", CommunityArgument.community())
                                                .suggests(COMMUNITY_SUGGESTIONS)
                                                .executes(ctx -> title(ctx, false, resolveCommunity(ctx,
                                                        CommunityArgument.getCommunity(ctx, "community"))))))))
                .then(Commands.literal("list")
                        .executes(ctx -> titleList(ctx, self(ctx)))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> titleList(ctx, EntityArgument.getPlayer(ctx, "player")))));
    }

    private static int titleGlobal(CommandContext<CommandSourceStack> ctx, boolean grant)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ResourceLocation titleId = ResourceLocationArgument.getId(ctx, "title");
        MinecraftServer server = ctx.getSource().getServer();

        boolean changed = grant
                ? TitleService.grantGlobal(server, target.getUUID(), target, titleId)
                : TitleService.revoke(server, target.getUUID(), null, titleId);

        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} {} global title {} {} {}: {}",
                ctx.getSource().getTextName(), grant ? "granted" : "revoked", titleId,
                grant ? "to" : "from", target.getGameProfile().getName(),
                changed ? "applied" : "no change");

        ctx.getSource().sendSuccess(() -> Component.translatable(changed
                        ? (grant ? "mcareputation.command.title.granted" : "mcareputation.command.title.revoked")
                        : "mcareputation.command.title.unchanged",
                Component.literal(titleId.toString()), target.getDisplayName()), true);
        return changed ? 1 : 0;
    }

    private static int title(CommandContext<CommandSourceStack> ctx, boolean grant,
                             CommunityKey community) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ResourceLocation titleId = ResourceLocationArgument.getId(ctx, "title");
        MinecraftServer server = ctx.getSource().getServer();

        boolean changed = grant
                ? TitleService.grant(server, target.getUUID(), target, community, titleId)
                : TitleService.revoke(server, target.getUUID(), community, titleId);

        McaReputation.LOGGER.info("[MCA: Reputation] AUDIT {} {} title {} {} {}: {}",
                ctx.getSource().getTextName(), grant ? "granted" : "revoked", titleId,
                grant ? "to" : "from", target.getGameProfile().getName(),
                changed ? "applied" : "no change");

        ctx.getSource().sendSuccess(() -> Component.translatable(changed
                        ? (grant ? "mcareputation.command.title.granted" : "mcareputation.command.title.revoked")
                        : "mcareputation.command.title.unchanged",
                Component.literal(titleId.toString()), target.getDisplayName()), true);
        return changed ? 1 : 0;
    }

    private static int titleList(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        MinecraftServer server = ctx.getSource().getServer();
        Optional<PlayerReputationRecord> record = ReputationSavedData.get(server).player(target.getUUID());
        if (record.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.title.none",
                    target.getDisplayName()), false);
            return 0;
        }
        int count = record.get().globalTitles().size();
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.title.header",
                target.getDisplayName()), false);
        record.get().globalTitles().forEach(titleId -> ctx.getSource().sendSuccess(() ->
                Component.literal(" • ").append(Titles.getOrUnknown(titleId).name())
                        .append(Component.literal(" (global)").withStyle(ChatFormatting.DARK_GRAY)), false));
        for (CommunityReputationRecord community : record.get().communities()) {
            for (ResourceLocation titleId : community.titles()) {
                ctx.getSource().sendSuccess(() -> Component.literal(" • ")
                        .append(Titles.getOrUnknown(titleId).name())
                        .append(Component.literal(" (" + community.key().asString() + ")")
                                .withStyle(ChatFormatting.DARK_GRAY)), false);
            }
            count += community.titles().size();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Data and diagnostics
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildTiers() {
        return Commands.literal("tiers")
                .executes(ctx -> tiers(ctx, ReputationTiers.DEFAULT_ID))
                .then(Commands.argument("ladder", ResourceLocationArgument.id())
                        .suggests(LADDER_SUGGESTIONS)
                        .executes(ctx -> tiers(ctx, ResourceLocationArgument.getId(ctx, "ladder"))));
    }

    private static int tiers(CommandContext<CommandSourceStack> ctx, ResourceLocation ladderId) {
        ReputationTierSet ladder = ReputationTiers.getOrDefault(ladderId);
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.tiers.header",
                Component.literal(ladderId.toString()), ladder.size()), false);
        for (ReputationTier tier : ladder.tiers()) {
            ctx.getSource().sendSuccess(() -> Component.literal(" • ")
                    .append(tier.name())
                    .append(Component.literal(" ≥ " + tier.threshold()
                            + "  (trust " + (tier.trustBias() >= 0 ? "+" : "") + tier.trustBias()
                            + ", respect " + (tier.respectBias() >= 0 ? "+" : "") + tier.respectBias() + ")")
                            .withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        return ladder.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildValidate() {
        return Commands.literal("validate")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    List<ReputationContentValidator.Problem> problems = ReputationContentValidator
                            .validate(IncidentRegistry.all(), ReputationTiers.allWithFallback(),
                                    Titles.all());
                    if (problems.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.translatable(
                                "mcareputation.command.validate.ok", IncidentRegistry.size(),
                                ReputationTiers.ids().size(), Titles.ids().size()), false);
                        return 1;
                    }
                    // Errors fail the command; advice alone does not — the same partition strict
                    // reloads use, so the two can never disagree about whether a pack is "fine".
                    long errors = problems.stream()
                            .filter(ReputationContentValidator.Problem::isError).count();
                    if (errors > 0) {
                        ctx.getSource().sendFailure(Component.translatable(
                                "mcareputation.command.validate.problems", problems.size()));
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.translatable(
                                "mcareputation.command.validate.warnings", problems.size()), false);
                    }
                    problems.forEach(problem -> ctx.getSource().sendSystemMessage(
                            Component.literal(" • " + problem)
                                    .withStyle(problem.isError() ? ChatFormatting.RED
                                            : ChatFormatting.YELLOW)));
                    return errors > 0 ? 0 : 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildReload() {
        return Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    // Datapack registries are owned by the vanilla reload cycle, so this defers to it
                    // rather than running a second, divergent load path (§24).
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                            "mcareputation.command.reload.delegating"), true);
                    ctx.getSource().getServer().getCommands()
                            .performPrefixedCommand(ctx.getSource().withPermission(4), "reload");
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildMigrate() {
        return Commands.literal("migrate")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> migrateStatus(ctx, self(ctx)))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> migrateStatus(ctx,
                                        EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("run")
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(ctx -> migrateRun(ctx, false))
                                .then(Commands.literal("--dry-run")
                                        .executes(ctx -> migrateRun(ctx, true)))));
    }

    private static int migrateStatus(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        MinecraftServer server = ctx.getSource().getServer();
        Optional<PlayerReputationRecord> record = ReputationSavedData.get(server).player(target.getUUID());
        List<String> providers = LegacyImportProviders.providerNames();
        ctx.getSource().sendSuccess(() -> Component.translatable("mcareputation.command.migrate.status",
                target.getDisplayName(),
                Component.literal(providers.isEmpty() ? "none" : String.join(", ", providers))), false);
        record.ifPresent(value -> value.migrationMarkers().forEach((source, version) ->
                ctx.getSource().sendSuccess(() -> Component.literal(" • " + source + " → v" + version)
                        .withStyle(ChatFormatting.GRAY), false)));
        if (record.isEmpty() || record.get().migrationMarkers().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(" • ")
                    .append(Component.translatable("mcareputation.command.migrate.none"))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int migrateRun(CommandContext<CommandSourceStack> ctx, boolean dryRun)
            throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        int applied = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(ctx, "player")) {
            List<ImportResult> results = dryRun
                    ? LegacyImportProviders.previewFor(server, target, true)
                    : LegacyImportProviders.runFor(server, target, true);
            if (results.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.translatable(
                        "mcareputation.command.migrate.nothing", target.getDisplayName()), false);
                continue;
            }
            for (ImportResult result : results) {
                ctx.getSource().sendSuccess(() -> Component.literal(target.getGameProfile().getName()
                        + ": " + result.summary()), true);
                if (result.applied()) {
                    applied++;
                }
            }
        }
        return applied;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDebug() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("community").executes(ReputationCommand::debugCommunity))
                .then(Commands.literal("witnesses").executes(ReputationCommand::debugWitnesses))
                .then(Commands.literal("integrations").executes(ReputationCommand::debugIntegrations))
                .then(Commands.literal("authorities").executes(ReputationCommand::debugAuthorities))
                .then(Commands.literal("standing")
                        .executes(ctx -> debugStanding(ctx, self(ctx), null))
                        .then(Commands.argument("community", CommunityArgument.community())
                                .suggests(COMMUNITY_SUGGESTIONS)
                                .executes(ctx -> debugStanding(ctx, self(ctx),
                                        CommunityArgument.getCommunity(ctx, "community"))))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> debugStanding(ctx,
                                        EntityArgument.getPlayer(ctx, "player"), null))
                                .then(Commands.argument("community", CommunityArgument.community())
                                        .suggests(COMMUNITY_SUGGESTIONS)
                                        .executes(ctx -> debugStanding(ctx,
                                                EntityArgument.getPlayer(ctx, "player"),
                                                CommunityArgument.getCommunity(ctx, "community"))))));
    }

    /**
     * Who is currently producing the natively detected deeds, and which companion integrations are
     * switched on. The first question an operator asks when an attack scores twice, or not at all.
     */
    private static int debugIntegrations(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("api version: " + McaReputationApi.getApiVersion()), false);
        source.sendSuccess(() -> Component.literal("integrations: quests="
                + McaReputationConfig.questsIntegrationEnabled()
                + " conversations=" + McaReputationConfig.conversationsIntegrationEnabled()
                + " crime=" + McaReputationConfig.crimeIntegrationEnabled()), false);
        List<String> authorities = CoreIncidentAuthorities.registeredNames();
        source.sendSuccess(() -> Component.literal("core-incident authorities registered: "
                + authorities.size()), false);
        authorities.forEach(name -> source.sendSuccess(() ->
                Component.literal("  " + name).withStyle(ChatFormatting.DARK_GRAY), false));
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            List<String> claimants = CoreIncidentAuthorities.claimantsOf(kind);
            source.sendSuccess(() -> Component.literal("  " + kind + " -> "
                    + (claimants.isEmpty() ? "native detection ON"
                            : "claimed by " + String.join(", ", claimants) + " (native detection OFF)")), false);
        }
        return authorities.size();
    }

    /**
     * Everything the standing pipeline believes about one player, in one screenful.
     *
     * <p>This exists because a player reported "no matter what I do I have 25 more to acquaintance",
     * and there was no way to tell from inside the game whether the stored number was not moving or
     * the screen was reading a different community from the one being written to. Those are opposite
     * bugs with the same symptom, and separating them took a source checkout. Every line below is one
     * of the hops between a deed and the pixels:
     *
     * <ul>
     *   <li><b>store</b> - which record this mod is reading, and whether one exists at all;</li>
     *   <li><b>score/baseline</b> - the raw stored value, before any tier or format arithmetic;</li>
     *   <li><b>tier / next / remaining</b> - all derived from that same score and printed beside it,
     *       so a frozen figure can be told from a frozen <em>lookup</em>;</li>
     *   <li><b>screen would select</b> - the community the standing screen opens on for this player
     *       when nothing in particular was asked about, and whether they have a record there. When
     *       that line names a different community from the one being written to, the score is moving
     *       and the screen is looking somewhere else;</li>
     *   <li><b>mirrors / MCA</b> - the two things outside this mod that can silently swallow a deed.</li>
     * </ul>
     */
    private static int debugStanding(CommandContext<CommandSourceStack> ctx, ServerPlayer subject,
                                     @Nullable String rawCommunity) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        long gameTime = server.overworld().getGameTime();
        ReputationSavedData data = ReputationSavedData.get(server);

        source.sendSuccess(() -> Component.literal("standing for " + subject.getGameProfile().getName()
                + " (" + subject.getUUID() + ")"), false);
        source.sendSuccess(() -> Component.literal(
                "  store: mcareputation SavedData (canonical; this mod has no second backend)")
                .withStyle(ChatFormatting.DARK_GRAY), false);

        List<CommunityKey> known = data.player(subject.getUUID())
                .map(record -> record.communities().stream()
                        .map(CommunityReputationRecord::key).toList())
                .orElse(List.of());
        source.sendSuccess(() -> Component.literal("  records: " + known.size() + " community/ies "
                + known.stream().map(CommunityKey::asString).limit(8).toList()), false);

        // What the screen would open on, unprompted. This is the READ key; the WRITE key is whichever
        // community the deed's villager belongs to, and the two diverging is invisible from the UI.
        Optional<CommunityKey> here = CommunityResolver.resolveAround(subject);
        boolean knowsHere = here.isPresent() && data.knows(subject.getUUID(), here.get());
        Optional<CommunityKey> best = ReputationService
                .knownCommunities(server, subject.getUUID(), gameTime).stream()
                .findFirst().map(ReputationSnapshot::community);
        Optional<CommunityKey> screen =
                SnapshotSelection.unprompted(here, knowsHere, best);
        source.sendSuccess(() -> Component.literal("  standing in: "
                + here.map(CommunityKey::asString).orElse("(no village within "
                        + McaReputationConfig.villageSearchRadius() + " blocks)")
                + (here.isPresent() ? (knowsHere ? " [has record]" : " [NO record]") : "")), false);
        source.sendSuccess(() -> Component.literal("  screen would select: "
                + screen.map(CommunityKey::asString).orElse("(nothing)")), false);

        CommunityKey community = rawCommunity != null
                ? resolveCommunity(ctx, rawCommunity)
                : screen.orElseThrow(NO_COMMUNITY::create);
        source.sendSuccess(() -> Component.literal("  inspecting: " + community.asString()), false);

        Optional<ReputationSnapshot> snapshot =
                ReputationService.snapshot(server, subject.getUUID(), community, gameTime);
        if (snapshot.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  NO RECORD for this community - the screen "
                    + "shows a synthesised floor-tier detail here (score 0), which is not a stored value")
                    .withStyle(ChatFormatting.YELLOW), false);
        } else {
            ReputationSnapshot snap = snapshot.get();
            source.sendSuccess(() -> Component.literal("  score: " + snap.score()
                    + "  baseline: " + snap.baseline()
                    + "  incidents: " + snap.totalIncidentCount()
                    + "  ladder: " + snap.ladder()), false);
            source.sendSuccess(() -> Component.literal("  tier: " + snap.tierId()
                    + " (>= " + snap.tier().threshold() + ")"
                    + "  high-water: " + snap.highWaterTierId().orElse("(none)")), false);
            source.sendSuccess(() -> Component.literal("  next: "
                    + snap.nextTier().map(next -> next.id() + " (>= " + next.threshold() + ")")
                            .orElse("(top of ladder)")
                    + "  remaining: " + snap.pointsToNextTier()
                            .map(String::valueOf).orElse("(none)")), false);
        }

        source.sendSuccess(() -> Component.literal("  mirrors: "
                + (ReputationService.mirrors().isEmpty() ? "(none registered)"
                        : ReputationService.mirrors().stream()
                                .map(dev.otectus.mcareputation.api.ReputationMirror::mirrorName).toList())
                + "  quests-integration: " + McaReputationConfig.questsIntegrationEnabled()
                + "  enabled: " + McaReputationConfig.enabled())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  MCA binding: "
                + (McaReflect.isAvailable() ? "active" : "UNAVAILABLE")
                + " (root " + McaReflect.root() + ", " + McaReflect.installedMca() + ")")
                .withStyle(McaReflect.isAvailable() ? ChatFormatting.DARK_GRAY : ChatFormatting.RED), false);
        return snapshot.map(ReputationSnapshot::score).orElse(0);
    }

    /**
     * Which mod is detecting each core deed.
     *
     * <p>The question this answers is "why have villager assaults stopped appearing in the ledger",
     * and without it the answer lives in another mod's config and is effectively unfindable. A kind
     * shown as claimed is being recorded by the named companion instead of by this mod; a kind shown
     * as unclaimed is detected here, whatever is registered.
     */
    private static int debugAuthorities(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> registered = CoreIncidentAuthorities.registeredNames();
        source.sendSuccess(() -> Component.literal(registered.isEmpty()
                ? "no core incident authorities registered; this mod detects everything itself"
                : "registered core incident authorities: " + String.join(", ", registered)), false);

        int claimed = 0;
        for (CoreIncidentKind kind : CoreIncidentKind.values()) {
            List<String> claimants = CoreIncidentAuthorities.claimantsOf(kind);
            if (!claimants.isEmpty()) {
                claimed++;
            }
            String detail = claimants.isEmpty()
                    ? "detected by MCA: Reputation"
                    : "claimed by " + String.join(", ", claimants);
            source.sendSuccess(() -> Component.literal("  " + kind.name() + " (" + kind.incidentType()
                    + "): " + detail).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return claimed;
    }

    private static int debugCommunity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Optional<CommunityKey> here = source.getEntity() != null
                ? CommunityResolver.resolveAround(source.getEntity())
                : Optional.empty();
        source.sendSuccess(() -> Component.literal("here: "
                + here.map(CommunityKey::asString).orElse("(none within "
                        + McaReputationConfig.villageSearchRadius() + " blocks)")), false);
        List<CommunityKey> known = ReputationSavedData.get(server).allKnownCommunities();
        source.sendSuccess(() -> Component.literal("known communities: " + known.size()), false);
        known.stream().limit(30).forEach(key -> source.sendSuccess(() ->
                Component.literal("  " + key.asString()).withStyle(ChatFormatting.DARK_GRAY), false));
        return known.size();
    }

    private static int debugWitnesses(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.translatable("mcareputation.command.error.player_only"));
            return 0;
        }
        var witnesses = dev.otectus.mcareputation.incident.WitnessResolver.resolve(level,
                player.position(), player, null, false);
        source.sendSuccess(() -> Component.literal("witnesses within "
                + McaReputationConfig.witnessRadius() + " blocks (line of sight "
                + (McaReputationConfig.requireWitnessLineOfSight() ? "required" : "off") + "): "
                + witnesses.size()), false);
        witnesses.forEach(uuid -> source.sendSuccess(() ->
                Component.literal("  " + uuid).withStyle(ChatFormatting.DARK_GRAY), false));
        return witnesses.size();
    }

    // ------------------------------------------------------------------
    // Argument helpers
    // ------------------------------------------------------------------

    private static ServerPlayer self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    /**
     * Resolves a community argument. {@code null} or {@code "here"} means "nearest to the executor",
     * resolved server-side; anything else must be an explicit {@code dimension/villageId}.
     *
     * <p>A bare integer is rejected on purpose. Village ids are allocated per dimension, so an
     * unqualified one names two different places in a world with a Nether village — exactly the
     * ambiguity {@link CommunityKey} exists to remove.
     */
    private static CommunityKey resolveCommunity(CommandContext<CommandSourceStack> ctx, String raw)
            throws CommandSyntaxException {
        if (raw == null || raw.isBlank() || HERE.equalsIgnoreCase(raw)) {
            if (ctx.getSource().getEntity() == null) {
                throw NO_COMMUNITY.create();
            }
            return CommunityResolver.resolveAround(ctx.getSource().getEntity())
                    .orElseThrow(NO_COMMUNITY::create);
        }
        return CommunityKey.tryParse(raw).orElseThrow(BAD_COMMUNITY::create);
    }

    /** Unused today, kept so the "no record" error has one canonical construction site. */
    static CommandSyntaxException noRecord() {
        return NO_RECORD.create();
    }
}
