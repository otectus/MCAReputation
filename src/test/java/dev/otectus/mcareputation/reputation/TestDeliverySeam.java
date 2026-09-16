package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.GossipStory;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.IncidentQuery;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The delivery transaction, reachable from tests outside this package.
 *
 * <p>{@code ServiceContext} and the {@code *With} seams are deliberately package-private — they are a
 * seam, not API — so the receipt, compaction and migration tests that live in {@code state} need one
 * public door into them. This is that door and nothing else: an in-memory store whose reference can be
 * swapped for a reloaded one, an explicit clock, and a recording event bus.
 *
 * <p>It also carries the failure injectors the transaction contract needs. Only the four methods this
 * interface declares can be made to fail from outside the service, which is exactly why they are the
 * boundaries worth injecting at: a clock read and an online-player lookup are the last things that can
 * throw before the canonical mutation, and a listener is the first thing that can throw after it.
 */
public final class TestDeliverySeam implements ServiceContext {

    private final List<Event> posted = new ArrayList<>();

    private ReputationSavedData data = ReputationSavedData.createForTest();
    private ReputationPolicy policy;
    private long gameTime;

    @Nullable
    private Consumer<Event> listener;
    private int clockReads;
    private int failClockRead = -1;
    private boolean failOnlinePlayerLookup;

    // --- ServiceContext -----------------------------------------------------

    @Override
    public boolean isServerThread() {
        return true;
    }

    @Override
    public ReputationSavedData data() {
        return data;
    }

    @Override
    @Nullable
    public ServerPlayer onlinePlayer(UUID playerId) {
        if (failOnlinePlayerLookup) {
            throw new InjectedFailure("online-player lookup");
        }
        return null;
    }

    @Override
    public void post(Event event) {
        // Recorded first, then handed to the listener: that is the order the real bus delivers in, so a
        // listener that throws has already been observed by everyone registered before it.
        posted.add(event);
        if (listener != null) {
            listener.accept(event);
        }
    }

    @Override
    public long now() {
        clockReads++;
        if (failClockRead == clockReads) {
            throw new InjectedFailure("clock read " + clockReads);
        }
        return gameTime;
    }

    @Override
    public ReputationPolicy policy() {
        return policy != null ? policy : ServiceContext.super.policy();
    }

    // --- test controls ------------------------------------------------------

    public TestDeliverySeam policy(ReputationPolicy replacement) {
        this.policy = replacement;
        return this;
    }

    public TestDeliverySeam gameTime(long value) {
        this.gameTime = value;
        return this;
    }

    public long gameTime() {
        return gameTime;
    }

    public ReputationSavedData store() {
        return data;
    }

    /** Replaces the live store, which is what "the server restarted" looks like from in here. */
    public void store(ReputationSavedData replacement) {
        this.data = replacement;
    }

    /** A full save/load cycle through the real NBT path, leaving the seam pointed at the reload. */
    public ReputationSavedData reload() {
        this.data = data.roundTripForTest();
        return this.data;
    }

    public List<Event> posted() {
        return List.copyOf(posted);
    }

    public int postedCount() {
        return posted.size();
    }

    public void clearPosted() {
        posted.clear();
    }

    /** Runs after each posted event is recorded. Throw from here to be a broken add-on; query the
     * store from here to be a synchronous consumer reacting to the commit. */
    public TestDeliverySeam listener(@Nullable Consumer<Event> value) {
        this.listener = value;
        return this;
    }

    /** Makes the {@code n}-th clock read of the seam's lifetime throw. 1-based; -1 disables. */
    public TestDeliverySeam failClockRead(int n) {
        this.failClockRead = n;
        return this;
    }

    public int clockReads() {
        return clockReads;
    }

    /** Makes every online-player lookup throw: the last failure a commit can suffer before it writes. */
    public TestDeliverySeam failOnlinePlayerLookup(boolean value) {
        this.failOnlinePlayerLookup = value;
        return this;
    }

    /** Distinguishable from a genuine bug in the code under test. */
    public static final class InjectedFailure extends RuntimeException {

        public InjectedFailure(String where) {
            super("injected failure at " + where);
        }
    }

    // --- the transaction ----------------------------------------------------

    public DeliveryOutcome deliver(IncidentDelivery delivery) {
        return ReputationService.deliverWith(this, delivery);
    }

    public ReputationResult record(ReputationRequest request) {
        return ReputationService.recordWith(this, request);
    }

    public ReputationResult recordSuperseding(ReputationRequest successor,
                                              dev.otectus.mcareputation.api.SupersedeSpec spec) {
        return ReputationService.recordSupersedingWith(this, successor, spec);
    }

    public Optional<ReceiptView> findReceipt(String namespace, UUID player, CommunityKey community,
                                             String operationKey) {
        return ReputationService.findReceiptWith(this, namespace, player, community, operationKey);
    }

    public Optional<ReputationIncidentView> findIncident(UUID player, CommunityKey community,
                                                         UUID incidentId) {
        return ReputationService.findIncidentViewWith(this, player, community, incidentId);
    }

    public OptionalLong receiptFloor(UUID player) {
        return ReputationService.receiptFloorWith(this, player);
    }

    public List<ReputationIncidentView> selectIncidents(UUID player, CommunityKey community,
                                                        IncidentQuery query,
                                                        @Nullable SpeakerContext speaker) {
        return ReputationService.selectIncidentsWith(this, player, community, query, speaker, gameTime);
    }

    public ResolutionResult resolveBound(UUID player, CommunityKey community, UUID incidentId,
                                         IncidentStatus status,
                                         net.minecraft.resources.ResourceLocation source,
                                         String operationKey) {
        return ReputationService.resolveBoundWith(this, player, community, incidentId, status, source,
                operationKey, gameTime);
    }

    public ResolutionResult resolve(UUID player, CommunityKey community, UUID incidentId,
                                    IncidentStatus status,
                                    net.minecraft.resources.ResourceLocation source) {
        return ReputationService.resolveWith(this, player, community, incidentId, status, source, gameTime);
    }

    public Optional<GossipStory> gossipStory(UUID player, CommunityKey community, UUID villager,
                                             boolean resident) {
        return ReputationService.gossipStoryWith(this, player, community, villager, resident, gameTime);
    }
}
