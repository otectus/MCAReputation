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
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The delivery transaction, reachable from tests outside this package.
 *
 * <p>{@code ServiceContext} and the {@code *With} seams are deliberately package-private — they are a
 * seam, not API — so the receipt, compaction and migration tests that live in {@code state} need one
 * public door into them. This is that door and nothing else: an in-memory store whose reference can be
 * swapped for a reloaded one, an explicit clock, and a recording event bus.
 */
public final class TestDeliverySeam implements ServiceContext {

    private final List<Event> posted = new ArrayList<>();

    private ReputationSavedData data = ReputationSavedData.createForTest();
    private ReputationPolicy policy;
    private long gameTime;

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
        return null;
    }

    @Override
    public void post(Event event) {
        posted.add(event);
    }

    @Override
    public long now() {
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

    // --- the transaction ----------------------------------------------------

    public DeliveryOutcome deliver(IncidentDelivery delivery) {
        return ReputationService.deliverWith(this, delivery);
    }

    public ReputationResult record(ReputationRequest request) {
        return ReputationService.recordWith(this, request);
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
