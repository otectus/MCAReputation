package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/**
 * The client's copy of what the server last told it (spec §27.3).
 *
 * <p>Strictly a cache, and strictly read-only. The client never computes a score, never guesses at a
 * tier, and never fills a gap with a plausible-looking number: with no snapshot the screen shows a
 * loading state and asks again (§35.1). Everything here arrived from the server, which is the only
 * authority on any of it.
 *
 * <p>Requests are paced by {@link RequestThrottle}: the server silently drops anything inside its
 * per-player cooldown, so an unpaced client request simply vanishes — which is how the screen used to
 * wedge on "asking around…" forever, and how two fast selector clicks left the header and the footer
 * describing different villages. The newest wish is parked and flushed from the screen's tick, and an
 * answer that never arrives times out into the retryable empty state.
 *
 * <p>Client-only by construction — loaded solely through {@code DistExecutor} from the packet handler
 * and from the client setup class, so a dedicated server never touches it.
 */
public final class ClientReputationData {

    private static final RequestThrottle THROTTLE = new RequestThrottle();

    private static List<ReputationNetwork.CommunitySummary> communities = List.of();
    private static Optional<ReputationNetwork.SelectedDetail> selected = Optional.empty();
    private static List<Component> globalTitles = List.of();

    /** Change packets buffered within one client tick, so several communities can merge (§28.3). */
    private static final java.util.List<ReputationNetwork.ChangeS2C> PENDING_CHANGES =
            new java.util.ArrayList<>();

    private ClientReputationData() {
    }

    // ------------------------------------------------------------------
    // Packet intake
    // ------------------------------------------------------------------

    public static void acceptSnapshot(ReputationNetwork.SnapshotS2C packet) {
        communities = packet.communities();
        selected = packet.selected();
        globalTitles = packet.globalTitles();
        THROTTLE.onReply();
        if (Minecraft.getInstance().screen instanceof ReputationScreen screen) {
            screen.onDataRefreshed();
        }
    }

    public static void openScreen() {
        // A push-opened screen (Journal link, future integrations) may be looking at another world's
        // or another moment's cache; ask for a fresh snapshot before showing anything.
        request(0, Optional.empty());
        Minecraft.getInstance().setScreen(new ReputationScreen(Minecraft.getInstance().screen));
    }

    public static void acceptChange(ReputationNetwork.ChangeS2C packet) {
        // Buffered to the end of the client tick: the server sends one packet per community, and
        // mergeChangeNotifications decides whether several arriving together become one line.
        PENDING_CHANGES.add(packet);
    }

    /** Flushes buffered change packets. Called at the end of every client tick. */
    static void flushChanges() {
        if (PENDING_CHANGES.isEmpty()) {
            return;
        }
        List<ReputationNetwork.ChangeS2C> batch = List.copyOf(PENDING_CHANGES);
        PENDING_CHANGES.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        boolean showNegative = McaReputationConfig.showNegativeTierMessages();
        boolean showActionBar = McaReputationConfig.showChangeActionBar();
        boolean showExact = McaReputationConfig.showExactScore();

        // Tier context goes to chat, which stacks — each village's news shows on its own line.
        for (ReputationNetwork.ChangeS2C packet : batch) {
            FeedbackPresentation.select(packet, showNegative, showActionBar, showExact)
                    .chat().ifPresent(line -> minecraft.player.displayClientMessage(line, false));
        }

        // The action bar holds one line. Merged: the deltas sum and the newest community labels the
        // total (the screen shows the exact breakdown). Unmerged: only the newest change shows —
        // racing several lines through the action bar would flash all but the last away anyway.
        ReputationNetwork.ChangeS2C newest = batch.get(batch.size() - 1);
        ReputationNetwork.ChangeS2C forActionBar = batch.size() > 1
                && McaReputationConfig.mergeChangeNotifications()
                ? new ReputationNetwork.ChangeS2C(newest.communityName(),
                        batch.stream().mapToInt(ReputationNetwork.ChangeS2C::delta).sum(),
                        newest.tierName(), false, false, false)
                : newest;
        FeedbackPresentation.select(forActionBar, showNegative, showActionBar, showExact)
                .actionBar().ifPresent(line -> minecraft.player.displayClientMessage(line, true));
    }

    public static void acceptToast(ReputationNetwork.TierToastS2C packet) {
        if (!McaReputationConfig.showTierToasts()) {
            return;
        }
        Minecraft.getInstance().getToasts().addToast(
                new ReputationTierToast(packet.communityName(), packet.tierName()));
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    /**
     * Asks the server for a fresh snapshot, or parks the wish if one went out too recently.
     *
     * @param contextEntityId the entity the player is interacting with, or {@code 0}. The server
     *                        validates this before honouring it; the client's claim carries no weight
     *                        of its own (§27.2).
     */
    public static void request(int contextEntityId, Optional<CommunityKey> community) {
        THROTTLE.offer(new RequestThrottle.Request(contextEntityId, community), now())
                .ifPresent(ClientReputationData::send);
    }

    public static void requestSelected(CommunityKey community) {
        request(0, Optional.ofNullable(community));
    }

    /** Called each screen tick so a parked request goes out the moment the cooldown allows. */
    static void tickRequests() {
        THROTTLE.due(now()).ifPresent(ClientReputationData::send);
    }

    private static void send(RequestThrottle.Request request) {
        PacketDistributor.sendToServer(
                new ReputationNetwork.RequestSnapshotC2S(request.contextEntityId(), request.community()));
    }

    private static long now() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public static List<ReputationNetwork.CommunitySummary> communities() {
        return communities;
    }

    public static Optional<ReputationNetwork.SelectedDetail> selected() {
        return selected;
    }

    public static List<Component> globalTitles() {
        return globalTitles;
    }

    /**
     * True while a request is outstanding: the screen shows "loading", never a guessed number. Times
     * out after {@link RequestThrottle#TIMEOUT_TICKS} so a lost packet degrades to the retryable
     * empty state rather than an eternal spinner.
     */
    public static boolean awaitingSnapshot() {
        return THROTTLE.awaiting(now());
    }

    /** Clears everything on disconnect so a different world cannot show the previous one's standing. */
    public static void clear() {
        communities = List.of();
        selected = Optional.empty();
        globalTitles = List.of();
        PENDING_CHANGES.clear();
        THROTTLE.reset();
    }
}
