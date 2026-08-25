package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 group 12, retargeted at the NeoForge payload codecs.
 *
 * <p>Everything goes through each payload's real {@code STREAM_CODEC} rather than a hand-called
 * encode/decode pair, so the test exercises exactly what the registrar will use at runtime. The
 * buffer is a {@link RegistryFriendlyByteBuf} because {@code ComponentSerialization.STREAM_CODEC}
 * needs registry context; {@link RegistryAccess#EMPTY} is enough for literal and translatable
 * components, which is all these payloads carry.
 */
class SnapshotPacketTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static ReputationNetwork.IncidentSummary incident(int contribution) {
        return new ReputationNetwork.IncidentSummary(UUID.randomUUID(), TestFixtures.ASSAULT,
                Component.translatable("mcareputation.incident.villager_assaulted"), 5000L,
                contribution, "active", "major", false);
    }

    private static ReputationNetwork.CommunitySummary community(int index) {
        return new ReputationNetwork.CommunitySummary(
                new dev.otectus.mcareputation.community.CommunityKey(
                        ResourceLocation.parse("minecraft:overworld"), index),
                "V" + index, index, "stranger");
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    void requestRoundTrips() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.encode(buf,
                new ReputationNetwork.RequestSnapshotC2S(42, Optional.of(TestFixtures.NETHER_3)));
        ReputationNetwork.RequestSnapshotC2S decoded =
                ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.decode(buf);
        assertEquals(42, decoded.contextEntityId());
        assertEquals(Optional.of(TestFixtures.NETHER_3), decoded.requestedCommunity());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void emptyRequestRoundTrips() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.encode(buf,
                new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty()));
        assertEquals(Optional.empty(),
                ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.decode(buf).requestedCommunity());
    }

    @Test
    void negativeScoresSurviveTheWire() {
        RegistryFriendlyByteBuf buf = buffer();
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
        RegistryFriendlyByteBuf buf = buffer();
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

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(communities, Optional.of(detail),
                        List.of(Component.literal("Wanderer"))));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);

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
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(List.of(), Optional.empty(), List.of()));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);
        assertTrue(decoded.communities().isEmpty());
        assertTrue(decoded.selected().isEmpty());
        assertTrue(decoded.globalTitles().isEmpty());
    }

    @Test
    void maximumSizedListsRoundTripWhole() {
        List<ReputationNetwork.CommunitySummary> exactlyMax = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_COMMUNITIES; i++) {
            exactlyMax.add(community(i));
        }
        List<ReputationNetwork.IncidentSummary> incidentsAtMax = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_INCIDENTS; i++) {
            incidentsAtMax.add(incident(-i));
        }
        List<Component> titlesAtMax = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_TITLES; i++) {
            titlesAtMax.add(Component.literal("Title " + i));
        }
        ReputationNetwork.SelectedDetail detail = new ReputationNetwork.SelectedDetail(
                TestFixtures.OVERWORLD_3, "Riverbend", 0, 0, "friend",
                Component.literal("Friend"), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                0, titlesAtMax, incidentsAtMax, ReputationBounds.MAX_SYNCED_INCIDENTS);

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(exactlyMax, Optional.of(detail), titlesAtMax));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);

        assertEquals(ReputationBounds.MAX_SYNCED_COMMUNITIES, decoded.communities().size());
        assertEquals(ReputationBounds.MAX_TITLES, decoded.globalTitles().size());
        assertEquals(ReputationBounds.MAX_SYNCED_INCIDENTS,
                decoded.selected().orElseThrow().incidents().size());
        assertEquals(ReputationBounds.MAX_TITLES, decoded.selected().orElseThrow().titles().size());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void changePacketRoundTripsBothPolarities() {
        for (int delta : new int[] {12, -12, 0}) {
            RegistryFriendlyByteBuf buf = buffer();
            ReputationNetwork.ChangeS2C.STREAM_CODEC.encode(buf, new ReputationNetwork.ChangeS2C(
                    Component.literal("Riverbend"), delta,
                    Component.translatable("mcareputation.tier.friend"), delta < 0, delta < 0,
                    delta > 0));
            ReputationNetwork.ChangeS2C decoded = ReputationNetwork.ChangeS2C.STREAM_CODEC.decode(buf);
            assertEquals(delta, decoded.delta());
            assertEquals(delta > 0, decoded.firstTime());
            assertEquals("Riverbend", decoded.communityName().getString());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void toastRoundTrips() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.TierToastS2C.STREAM_CODEC.encode(buf, new ReputationNetwork.TierToastS2C(
                Component.literal("Riverbend"),
                Component.translatable("mcareputation.tier.honored")));
        ReputationNetwork.TierToastS2C decoded = ReputationNetwork.TierToastS2C.STREAM_CODEC.decode(buf);
        assertEquals("Riverbend", decoded.communityName().getString());
        assertEquals(0, buf.readableBytes());
    }

    /** The empty payload carries nothing but its id, and still decodes to an equal value. */
    @Test
    void openScreenIsAUnitPayload() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.OpenScreenS2C.STREAM_CODEC.encode(buf, new ReputationNetwork.OpenScreenS2C());
        assertEquals(0, buf.readableBytes(), "an empty payload must not put bytes on the wire");
        assertEquals(new ReputationNetwork.OpenScreenS2C(),
                ReputationNetwork.OpenScreenS2C.STREAM_CODEC.decode(buf));
    }

    // ------------------------------------------------------------------
    // §27.3 encode-side truncation
    // ------------------------------------------------------------------

    /** An oversized ledger must be truncated before encoding, not sent whole. */
    @Test
    void oversizedListsAreBoundedBeforeEncoding() {
        List<ReputationNetwork.CommunitySummary> tooMany = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_COMMUNITIES * 3; i++) {
            tooMany.add(community(i));
        }
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(tooMany, Optional.empty(), List.of()));
        assertEquals(ReputationBounds.MAX_SYNCED_COMMUNITIES,
                ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf).communities().size());
    }

    @Test
    void oversizedTitleAndIncidentListsAreBoundedBeforeEncoding() {
        List<Component> tooManyTitles = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_TITLES * 2; i++) {
            tooManyTitles.add(Component.literal("Title " + i));
        }
        List<ReputationNetwork.IncidentSummary> tooManyIncidents = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_INCIDENTS * 2; i++) {
            tooManyIncidents.add(incident(-i));
        }
        ReputationNetwork.SelectedDetail detail = new ReputationNetwork.SelectedDetail(
                TestFixtures.OVERWORLD_3, "Riverbend", 0, 0, "friend",
                Component.literal("Friend"), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                0, tooManyTitles, tooManyIncidents, 999);

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(List.of(), Optional.of(detail), tooManyTitles));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);
        assertEquals(ReputationBounds.MAX_TITLES, decoded.globalTitles().size());
        assertEquals(ReputationBounds.MAX_TITLES, decoded.selected().orElseThrow().titles().size());
        assertEquals(ReputationBounds.MAX_SYNCED_INCIDENTS,
                decoded.selected().orElseThrow().incidents().size());
    }

    // ------------------------------------------------------------------
    // §27.3 decode-side rejection — new in the NeoForge port
    // ------------------------------------------------------------------

    /**
     * The Forge build bounded lists on the way out but read them back unbounded, so a hostile peer
     * could make the receiver allocate whatever it claimed. Each of these writes a hand-rolled buffer
     * whose declared count is exactly one over the limit and asserts the decoder refuses it.
     */
    @Test
    void aCommunityCountOverTheLimitIsRejected() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(ReputationBounds.MAX_SYNCED_COMMUNITIES + 1);
        assertThrows(DecoderException.class,
                () -> ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf));
    }

    @Test
    void aGlobalTitleCountOverTheLimitIsRejected() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(0);        // communities: empty
        buf.writeBoolean(false);   // selected: absent
        buf.writeVarInt(ReputationBounds.MAX_TITLES + 1);
        assertThrows(DecoderException.class,
                () -> ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf));
    }

    @Test
    void aSelectedTitleCountOverTheLimitIsRejected() {
        assertThrows(DecoderException.class, () -> {
            RegistryFriendlyByteBuf buf = buffer();
            writeSelectedDetailHeader(buf);
            buf.writeVarInt(ReputationBounds.MAX_TITLES + 1);
            ReputationNetwork.SelectedDetail.read(buf);
        });
    }

    @Test
    void aSelectedIncidentCountOverTheLimitIsRejected() {
        assertThrows(DecoderException.class, () -> {
            RegistryFriendlyByteBuf buf = buffer();
            writeSelectedDetailHeader(buf);
            buf.writeVarInt(0); // titles: empty
            buf.writeVarInt(ReputationBounds.MAX_SYNCED_INCIDENTS + 1);
            ReputationNetwork.SelectedDetail.read(buf);
        });
    }

    @Test
    void aNegativeCountIsRejectedRatherThanTreatedAsEmpty() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(-1);
        assertThrows(DecoderException.class,
                () -> ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf));
    }

    /** Everything in a SelectedDetail up to, but not including, its first bounded list. */
    private static void writeSelectedDetailHeader(RegistryFriendlyByteBuf buf) {
        TestFixtures.OVERWORLD_3.write(buf);
        buf.writeUtf("Riverbend", 64);
        buf.writeInt(0);           // score
        buf.writeInt(0);           // baseline
        buf.writeUtf("friend", 48);
        net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC
                .encode(buf, Component.literal("Friend"));
        buf.writeBoolean(false);   // tierDescription: absent
        buf.writeInt(0);           // tierThreshold
        buf.writeBoolean(false);   // nextTierId: absent
        buf.writeBoolean(false);   // nextTierName: absent
        buf.writeInt(0);           // nextThreshold
    }

    // ------------------------------------------------------------------
    // Strings and identity
    // ------------------------------------------------------------------

    /** A maximum-length village name survives; one byte longer must not be accepted. */
    @Test
    void communityNamesAreBoundedInBothDirections() {
        String maximal = "n".repeat(
                dev.otectus.mcareputation.community.CommunityMetadata.MAX_NAME_LENGTH);
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.CommunitySummary.write(buf,
                new ReputationNetwork.CommunitySummary(TestFixtures.OVERWORLD_3, maximal, 0, "friend"));
        assertEquals(maximal, ReputationNetwork.CommunitySummary.read(buf).name());

        RegistryFriendlyByteBuf overlong = buffer();
        TestFixtures.OVERWORLD_3.write(overlong);
        overlong.writeUtf("n".repeat(
                dev.otectus.mcareputation.community.CommunityMetadata.MAX_NAME_LENGTH + 1));
        overlong.writeInt(0);
        overlong.writeUtf("friend", 48);
        assertThrows(DecoderException.class,
                () -> ReputationNetwork.CommunitySummary.read(overlong));
    }

    /** A hostile or corrupt packet must not be able to produce an impossible key (§27.2). */
    @Test
    void aNegativeVillageIdOnTheWireIsClampedNotThrown() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(ResourceLocation.parse("minecraft:overworld"));
        buf.writeVarInt(-5);
        assertEquals(0, dev.otectus.mcareputation.community.CommunityKey.read(buf).villageId());
    }

    /** Five payloads, five distinct ids, all in this mod's namespace. */
    @Test
    void everyPayloadIdIsUniqueAndNamespaced() {
        List<ResourceLocation> ids = List.of(
                ReputationNetwork.RequestSnapshotC2S.TYPE.id(),
                ReputationNetwork.SnapshotS2C.TYPE.id(),
                ReputationNetwork.OpenScreenS2C.TYPE.id(),
                ReputationNetwork.ChangeS2C.TYPE.id(),
                ReputationNetwork.TierToastS2C.TYPE.id());
        assertEquals(5, ids.size());
        assertEquals(ids.size(), Set.copyOf(ids).size(), "payload ids must be unique");
        ids.forEach(id -> assertEquals(dev.otectus.mcareputation.McaReputation.MOD_ID, id.getNamespace()));
        assertNotEquals(ReputationNetwork.SnapshotS2C.TYPE, ReputationNetwork.ChangeS2C.TYPE);
    }
}
