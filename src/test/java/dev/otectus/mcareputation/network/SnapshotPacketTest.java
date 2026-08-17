package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 12. */
class SnapshotPacketTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static ReputationNetwork.IncidentSummary incident(int contribution) {
        return new ReputationNetwork.IncidentSummary(UUID.randomUUID(), TestFixtures.ASSAULT,
                Component.translatable("mcareputation.incident.villager_assaulted"), 5000L,
                contribution, "active", "major", false);
    }

    @Test
    void requestRoundTrips() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.RequestSnapshotC2S.encode(
                new ReputationNetwork.RequestSnapshotC2S(42, Optional.of(TestFixtures.NETHER_3)), buf);
        ReputationNetwork.RequestSnapshotC2S decoded =
                ReputationNetwork.RequestSnapshotC2S.decode(buf);
        assertEquals(42, decoded.contextEntityId());
        assertEquals(Optional.of(TestFixtures.NETHER_3), decoded.requestedCommunity());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void emptyRequestRoundTrips() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.RequestSnapshotC2S.encode(
                new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty()), buf);
        assertEquals(Optional.empty(),
                ReputationNetwork.RequestSnapshotC2S.decode(buf).requestedCommunity());
    }

    @Test
    void negativeScoresSurviveTheWire() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.CommunitySummary.write(buf,
                new ReputationNetwork.CommunitySummary(TestFixtures.OVERWORLD_3, "Riverbend", -412,
                        "distrusted"));
        ReputationNetwork.CommunitySummary decoded = ReputationNetwork.CommunitySummary.read(buf);
        assertEquals(-412, decoded.score());
        assertEquals("distrusted", decoded.tierId());
        assertEquals("Riverbend", decoded.name());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void incidentSummaryRoundTripsIncludingNegativeContribution() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.IncidentSummary original = incident(-40);
        ReputationNetwork.IncidentSummary.write(buf, original);
        ReputationNetwork.IncidentSummary decoded = ReputationNetwork.IncidentSummary.read(buf);
        assertEquals(original.id(), decoded.id());
        assertEquals(original.type(), decoded.type());
        assertEquals(-40, decoded.contribution());
        assertEquals(original.display().getString(), decoded.display().getString());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void fullSnapshotRoundTrips() {
        List<ReputationNetwork.CommunitySummary> communities = List.of(
                new ReputationNetwork.CommunitySummary(TestFixtures.OVERWORLD_3, "Riverbend", 90, "friend"),
                new ReputationNetwork.CommunitySummary(TestFixtures.NETHER_3, "", -30, "wary"));
        ReputationNetwork.SelectedDetail detail = new ReputationNetwork.SelectedDetail(
                TestFixtures.OVERWORLD_3, "Riverbend", 90, 10, "friend",
                Component.translatable("mcareputation.tier.friend"),
                Optional.of(Component.translatable("mcareputation.tier.friend.description")), 75,
                Optional.of("honored"), Optional.of(Component.translatable("mcareputation.tier.honored")),
                150, List.of(Component.literal("Honored of the Village")),
                List.of(incident(-8), incident(12)), 64);

        FriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.encode(new ReputationNetwork.SnapshotS2C(communities,
                Optional.of(detail), List.of(Component.literal("Wanderer"))), buf);
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.decode(buf);

        assertEquals(2, decoded.communities().size());
        ReputationNetwork.SelectedDetail decodedDetail = decoded.selected().orElseThrow();
        assertEquals(90, decodedDetail.score());
        assertEquals(Optional.of("honored"), decodedDetail.nextTierId());
        assertEquals(2, decodedDetail.incidents().size());
        assertEquals(64, decodedDetail.totalIncidents(),
                "the true ledger size survives so the screen can say 'showing 2 of 64'");
        // Titles and the tier description cross the wire as resolved components: a dedicated-server
        // client has an empty Titles registry and could not resolve an id.
        assertEquals("Honored of the Village", decodedDetail.titles().get(0).getString());
        assertTrue(decodedDetail.tierDescription().isPresent());
        assertEquals(1, decoded.globalTitles().size());
        assertEquals("Wanderer", decoded.globalTitles().get(0).getString());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void emptySnapshotRoundTrips() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.encode(
                new ReputationNetwork.SnapshotS2C(List.of(), Optional.empty(), List.of()), buf);
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.decode(buf);
        assertTrue(decoded.communities().isEmpty());
        assertTrue(decoded.selected().isEmpty());
        assertTrue(decoded.globalTitles().isEmpty());
    }

    /** §27.3: an oversized ledger must be truncated before encoding, not sent whole. */
    @Test
    void oversizedListsAreBoundedBeforeEncoding() {
        List<ReputationNetwork.CommunitySummary> tooMany = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_COMMUNITIES * 3; i++) {
            tooMany.add(new ReputationNetwork.CommunitySummary(
                    new dev.otectus.mcareputation.community.CommunityKey(
                            new ResourceLocation("minecraft:overworld"), i), "V" + i, i, "stranger"));
        }
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.encode(
                new ReputationNetwork.SnapshotS2C(tooMany, Optional.empty(), List.of()), buf);
        assertEquals(ReputationBounds.MAX_SYNCED_COMMUNITIES,
                ReputationNetwork.SnapshotS2C.decode(buf).communities().size());
    }

    @Test
    void changePacketRoundTripsBothPolarities() {
        for (int delta : new int[] {12, -12, 0}) {
            FriendlyByteBuf buf = buffer();
            ReputationNetwork.ChangeS2C.encode(new ReputationNetwork.ChangeS2C(
                    Component.literal("Riverbend"), delta,
                    Component.translatable("mcareputation.tier.friend"), delta < 0, delta < 0,
                    delta > 0), buf);
            ReputationNetwork.ChangeS2C decoded = ReputationNetwork.ChangeS2C.decode(buf);
            assertEquals(delta, decoded.delta());
            assertEquals(delta > 0, decoded.firstTime());
            assertEquals("Riverbend", decoded.communityName().getString());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void toastRoundTrips() {
        FriendlyByteBuf buf = buffer();
        ReputationNetwork.TierToastS2C.encode(new ReputationNetwork.TierToastS2C(
                Component.literal("Riverbend"),
                Component.translatable("mcareputation.tier.honored")), buf);
        ReputationNetwork.TierToastS2C decoded = ReputationNetwork.TierToastS2C.decode(buf);
        assertEquals("Riverbend", decoded.communityName().getString());
        assertEquals(0, buf.readableBytes());
    }

    /** A hostile or corrupt packet must not be able to produce an impossible key (§27.2). */
    @Test
    void aNegativeVillageIdOnTheWireIsClampedNotThrown() {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(new ResourceLocation("minecraft:overworld"));
        buf.writeVarInt(-5);
        assertEquals(0, dev.otectus.mcareputation.community.CommunityKey.read(buf).villageId());
    }
}
