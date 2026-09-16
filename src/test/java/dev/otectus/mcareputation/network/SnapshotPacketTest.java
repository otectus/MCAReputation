package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.VillagerOpinion;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.incident.IncidentVisibility;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return incident(contribution, contribution, IncidentVisibility.WITNESSED, false, false);
    }

    private static ReputationNetwork.IncidentSummary incident(int contribution, int baseDelta,
                                                              IncidentVisibility visibility,
                                                              boolean decays, boolean superseded) {
        return new ReputationNetwork.IncidentSummary(UUID.randomUUID(), TestFixtures.ASSAULT,
                Component.translatable("mcareputation.incident.villager_assaulted"), 5000L,
                contribution, "active", "major", false, visibility, baseDelta, decays, superseded);
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
                new ReputationNetwork.RequestSnapshotC2S(42, Optional.of(TestFixtures.NETHER_3), 3));
        ReputationNetwork.RequestSnapshotC2S decoded =
                ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.decode(buf);
        assertEquals(42, decoded.contextEntityId());
        assertEquals(Optional.of(TestFixtures.NETHER_3), decoded.requestedCommunity());
        assertEquals(3, decoded.page());
        assertEquals(0, buf.readableBytes());
    }

    /** The page-less form is page 0, and a hostile negative page cannot survive the decode. */
    @Test
    void aRequestWithoutAPageIsPageZeroAndANegativePageIsClamped() {
        assertEquals(0, new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty()).page());
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.encode(buf,
                new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty(), -9));
        assertEquals(0, ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.decode(buf).page());
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

    /**
     * The four fields the screen needs to stop showing a settled deed as a fresh full penalty: what it
     * was worth originally, whether ordinary fading applies, whether another deed absorbed it, and who
     * ever knew about it.
     */
    @Test
    void aSettledIncidentCarriesItsOriginalDeltaVisibilityDecayAndSupersession() {
        for (IncidentVisibility visibility : IncidentVisibility.values()) {
            ReputationNetwork.IncidentSummary original =
                    incident(-6, -40, visibility, true, true);
            RegistryFriendlyByteBuf buf = buffer();
            ReputationNetwork.IncidentSummary.write(buf, original);
            ReputationNetwork.IncidentSummary decoded = ReputationNetwork.IncidentSummary.read(buf);
            assertEquals(-6, decoded.contribution());
            assertEquals(-40, decoded.baseDelta(), "the original deed survives beside the current one");
            assertEquals(visibility, decoded.visibility());
            assertTrue(decoded.decays());
            assertTrue(decoded.superseded());
            assertEquals(0, buf.readableBytes());
        }
    }

    /**
     * A full page is the largest summary list that can legitimately cross the wire, and it must
     * survive intact: the per-page cap is where truncation used to hide the rest of the villages.
     */
    @Test
    void aFullPageOfCommunitiesRoundTripsWithTheTrueTotalBesideIt() {
        List<ReputationNetwork.CommunitySummary> page = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_COMMUNITIES; i++) {
            page.add(community(i));
        }
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(page, Optional.empty(), List.of(), 2, 3,
                        ReputationBounds.MAX_SYNCED_COMMUNITIES * 2 + 5));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);
        assertEquals(ReputationBounds.MAX_SYNCED_COMMUNITIES, decoded.communities().size());
        assertEquals(2, decoded.page());
        assertEquals(3, decoded.pageCount());
        assertEquals(ReputationBounds.MAX_SYNCED_COMMUNITIES * 2 + 5, decoded.totalCommunities());
        assertEquals(0, buf.readableBytes());
    }

    /** A page index past the end of the reply cannot survive the decode. */
    @Test
    void aPageBeyondThePageCountIsClampedOnArrival() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(List.of(), Optional.empty(), List.of(), 9, 2, 70));
        assertEquals(1, ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf).page());
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
                List.of(incident(-8), incident(12)), 64, Optional.empty());

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(buf,
                new ReputationNetwork.SnapshotS2C(communities, Optional.of(detail),
                        List.of(Component.literal("Wanderer")), 1, 4, 210));
        ReputationNetwork.SnapshotS2C decoded = ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf);

        assertEquals(2, decoded.communities().size());
        assertEquals(1, decoded.page());
        assertEquals(4, decoded.pageCount());
        assertEquals(210, decoded.totalCommunities(),
                "the true community count travels beside the page, not the page's own size");
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

    /**
     * The opinion rides last on {@code SelectedDetail}, so everything before it keeps its offset. Both
     * shapes must survive: the screen opened from a villager, and the screen opened from the keybind.
     */
    @Test
    void theVillagerOpinionRoundTripsWhenPresentAndWhenAbsent() {
        for (Optional<ReputationNetwork.OpinionSummary> opinion : List.of(
                Optional.<ReputationNetwork.OpinionSummary>empty(),
                Optional.of(new ReputationNetwork.OpinionSummary(Component.literal("Anna"),
                        Component.translatable("mcareputation.tier.friend"),
                        VillagerOpinion.OpinionBasis.INVOLVED)))) {
            ReputationNetwork.SelectedDetail detail = new ReputationNetwork.SelectedDetail(
                    TestFixtures.OVERWORLD_3, "Riverbend", 90, 10, "friend",
                    Component.translatable("mcareputation.tier.friend"), Optional.empty(), 75,
                    Optional.empty(), Optional.empty(), 150, List.of(), List.of(incident(-8)), 1,
                    opinion);
            RegistryFriendlyByteBuf buf = buffer();
            ReputationNetwork.SelectedDetail.write(buf, detail);
            ReputationNetwork.SelectedDetail decoded = ReputationNetwork.SelectedDetail.read(buf);
            assertEquals(opinion.isPresent(), decoded.opinion().isPresent());
            assertEquals(90, decoded.score(), "the fields before the opinion still read correctly");
            opinion.ifPresent(summary -> {
                assertEquals("Anna", decoded.opinion().orElseThrow().villagerName().getString());
                assertEquals(VillagerOpinion.OpinionBasis.INVOLVED,
                        decoded.opinion().orElseThrow().basis());
            });
            assertEquals(0, buf.readableBytes());
        }
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
                0, titlesAtMax, incidentsAtMax, ReputationBounds.MAX_SYNCED_INCIDENTS,
                Optional.empty());

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
                0, tooManyTitles, tooManyIncidents, 999, Optional.empty());

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


    /** Components need the registry-aware codec here, exactly as they do on the wire. */
    private static void writeComponent(RegistryFriendlyByteBuf buf, Component component) {
        net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, component);
    }

    // ------------------------------------------------------------------
    // The 0.6.0 profile subpayload (§18.3)
    // ------------------------------------------------------------------

    private static ReputationNetwork.FacetSummary facet(ResourceLocation id, int value) {
        return new ReputationNetwork.FacetSummary(id, Component.translatable("mcareputation.facet.bravery"),
                Optional.of(Component.translatable("mcareputation.facet.bravery.brave")), value, -100,
                100, 3, 1, true);
    }

    private static ReputationNetwork.ProfileSummary profile(int traits, int details) {
        List<Component> dominant = new ArrayList<>();
        for (int i = 0; i < traits; i++) {
            dominant.add(Component.literal("Trait " + i));
        }
        List<ReputationNetwork.FacetSummary> facets = new ArrayList<>();
        for (int i = 0; i < details; i++) {
            facets.add(facet(ResourceLocation.fromNamespaceAndPath("mcareputation", "facet_" + i), i - 4));
        }
        return new ReputationNetwork.ProfileSummary(ProfileAvailability.AVAILABLE,
                ProfileCoverage.COMPLETE_SINCE_RECORD_START, false, Optional.empty(), 240,
                "well_known", Component.translatable("mcareputation.recognition.well_known"), 7,
                dominant, facets, 12L, 3L);
    }

    private static ReputationNetwork.VillagerProfileSummary observer(
            ReputationNetwork.ProfileSummary known) {
        return new ReputationNetwork.VillagerProfileSummary(TestFixtures.VILLAGER_1,
                Component.literal("Anna"), known, 18, -4, 14,
                Component.translatable("mcareputation.tier.friend"),
                VillagerProfileSnapshot.TraitBasis.RESOLVED, 1, 2, 3);
    }

    private static ReputationNetwork.SelectedDetail detailWith(
            Optional<ReputationNetwork.ProfileSummary> profile,
            Optional<ReputationNetwork.VillagerProfileSummary> observer) {
        return new ReputationNetwork.SelectedDetail(TestFixtures.OVERWORLD_3, "Riverbend", 90, 10,
                "friend", Component.translatable("mcareputation.tier.friend"), Optional.empty(), 75,
                Optional.empty(), Optional.empty(), 150, List.of(), List.of(incident(-8)), 1,
                Optional.empty(), profile, observer);
    }

    /** Both panes, every field, and nothing left in the buffer afterwards. */
    @Test
    void theProfileSubpayloadRoundTripsWithBothPanes() {
        ReputationNetwork.ProfileSummary community = profile(3, 8);
        ReputationNetwork.SelectedDetail detail =
                detailWith(Optional.of(community), Optional.of(observer(profile(2, 4))));

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SelectedDetail.write(buf, detail);
        ReputationNetwork.SelectedDetail decoded = ReputationNetwork.SelectedDetail.read(buf);

        ReputationNetwork.ProfileSummary read = decoded.profile().orElseThrow();
        assertEquals(ProfileAvailability.AVAILABLE, read.availability());
        assertEquals(ProfileCoverage.COMPLETE_SINCE_RECORD_START, read.coverage());
        assertEquals(240, read.recognition());
        assertEquals("well_known", read.recognitionTierId());
        assertEquals(7, read.recognitionEvidence());
        assertEquals(3, read.dominantTraits().size());
        assertEquals(8, read.details().size());
        assertEquals(12L, read.profileRevision());
        assertEquals(3L, read.definitionGeneration());
        ReputationNetwork.FacetSummary first = read.details().get(0);
        assertEquals(-4, first.value());
        assertEquals(3, first.supportingEvidence());
        assertEquals(1, first.opposingEvidence());
        assertTrue(first.label().isPresent());

        ReputationNetwork.VillagerProfileSummary view = decoded.villagerProfile().orElseThrow();
        assertEquals(TestFixtures.VILLAGER_1, view.villagerId(),
                "the observer identity travels, so a late reply about another villager is detectable");
        assertEquals("Anna", view.villagerName().getString());
        assertEquals(18, view.baseOpinion());
        assertEquals(-4, view.facetAdjustment());
        assertEquals(14, view.finalOpinion());
        assertEquals(VillagerProfileSnapshot.TraitBasis.RESOLVED, view.traitBasis());
        assertEquals(6, view.knownIncidents());
        assertEquals(4, view.known().details().size());
        assertEquals(90, decoded.score(), "the fields before the profile still read correctly");
        assertEquals(0, buf.readableBytes());
    }

    /** No profile, no observer: the 0.5.0 shape still crosses the wire unchanged. */
    @Test
    void aDetailWithNoProfileRoundTripsAsAbsent() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SelectedDetail.write(buf,
                detailWith(Optional.empty(), Optional.empty()));
        ReputationNetwork.SelectedDetail decoded = ReputationNetwork.SelectedDetail.read(buf);
        assertTrue(decoded.profile().isEmpty());
        assertTrue(decoded.villagerProfile().isEmpty());
        assertEquals(0, buf.readableBytes());
    }

    /**
     * An unavailable answer is a real answer and must survive intact: §18.2 needs the five states to
     * render differently, which they cannot do if the reason does not arrive.
     */
    @Test
    void everyUnavailableStateSurvivesWithItsReason() {
        for (ProfileAvailability availability : ProfileAvailability.values()) {
            for (ProfileCoverage coverage : ProfileCoverage.values()) {
                ReputationNetwork.ProfileSummary summary = new ReputationNetwork.ProfileSummary(
                        availability, coverage, true, Optional.of("partial_legacy_history"), 0, "",
                        Component.empty(), 0, List.of(), List.of(), 0L, 0L);
                RegistryFriendlyByteBuf buf = buffer();
                ReputationNetwork.ProfileSummary.write(buf, summary);
                ReputationNetwork.ProfileSummary decoded =
                        ReputationNetwork.ProfileSummary.read(buf);
                assertEquals(availability, decoded.availability());
                assertEquals(coverage, decoded.coverage());
                assertTrue(decoded.readOnlyStore());
                assertEquals(Optional.of("partial_legacy_history"), decoded.reason());
                assertEquals(0, buf.readableBytes());
            }
        }
    }

    /** §18.3: more than three traits or eight details is truncated before it is encoded. */
    @Test
    void anOversizedProfileIsTruncatedBeforeEncoding() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.ProfileSummary.write(buf, profile(9, 40));
        ReputationNetwork.ProfileSummary decoded = ReputationNetwork.ProfileSummary.read(buf);
        assertEquals(ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS, decoded.dominantTraits().size());
        assertEquals(ReputationBounds.MAX_SYNCED_FACET_DETAILS, decoded.details().size());
    }

    /**
     * §18.3's real requirement: the length is refused <b>before</b> a list is allocated. A claimed
     * count of two billion with no bytes behind it must be an immediate refusal naming the bound, not
     * an allocation followed by an underflow — and not an OutOfMemoryError.
     */
    @Test
    void anImpossibleTraitCountIsRefusedBeforeAnythingIsAllocated() {
        for (int claimed : new int[] {-1, Integer.MIN_VALUE, 4, 1_000, Integer.MAX_VALUE}) {
            RegistryFriendlyByteBuf buf = buffer();
            writeProfileHead(buf);
            buf.writeVarInt(claimed);
            DecoderException thrown = assertThrows(DecoderException.class,
                    () -> ReputationNetwork.ProfileSummary.read(buf),
                    () -> "a claimed trait count of " + claimed + " must be refused");
            // This branch's readBoundedList names the interval rather than Forge's "at most"; what
            // matters either way is that the refusal reports the bound it enforced.
            assertTrue(thrown.getMessage().contains("outside [0, "
                            + ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS + "]"),
                    "the refusal names the bound it enforced: " + thrown.getMessage());
        }
    }

    /** The same rule one list further in, where the detail entries are read. */
    @Test
    void anImpossibleDetailCountIsRefusedBeforeAnythingIsAllocated() {
        for (int claimed : new int[] {-7, 9, Integer.MAX_VALUE}) {
            RegistryFriendlyByteBuf buf = buffer();
            writeProfileHead(buf);
            buf.writeVarInt(0); // no dominant traits
            buf.writeVarInt(claimed);
            assertThrows(DecoderException.class, () -> ReputationNetwork.ProfileSummary.read(buf),
                    () -> "a claimed detail count of " + claimed + " must be refused");
        }
    }

    /** The lists that predate 0.6.0 are now bounded on decode too, not only truncated on encode. */
    @Test
    void anImpossibleCommunityCountIsRefusedBeforeAnythingIsAllocated() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(Integer.MAX_VALUE);
        assertThrows(DecoderException.class,
                () -> ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(buf));
    }

    /** Everything up to the first bounded list, so a test can forge the count that follows. */
    private static void writeProfileHead(RegistryFriendlyByteBuf buf) {
        buf.writeEnum(ProfileAvailability.AVAILABLE);
        buf.writeEnum(ProfileCoverage.COMPLETE_SINCE_RECORD_START);
        buf.writeBoolean(false);
        buf.writeBoolean(false); // reason: absent
        buf.writeVarInt(10);
        buf.writeUtf("noticed", 48);
        writeComponent(buf, Component.literal("Noticed"));
        buf.writeVarInt(1);
    }

    /**
     * §18.3's byte budget, measured rather than assumed: the largest legal profile pair — three
     * traits, eight details each, every label a full authored component — stays inside 16 KiB.
     */
    @Test
    void theLargestLegalProfilePairFitsTheByteBudget() {
        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SelectedDetail.write(buf,
                detailWith(Optional.of(profile(3, 8)), Optional.of(observer(profile(3, 8)))));
        RegistryFriendlyByteBuf bare = buffer();
        ReputationNetwork.SelectedDetail.write(bare, detailWith(Optional.empty(), Optional.empty()));
        int profileBytes = buf.writerIndex() - bare.writerIndex();
        assertTrue(profileBytes <= ReputationBounds.MAX_PROFILE_PAYLOAD_BYTES,
                "the profile subpayload took " + profileBytes + " bytes of its "
                        + ReputationBounds.MAX_PROFILE_PAYLOAD_BYTES + "-byte budget");
    }

    /**
     * And what happens when authored text blows the budget anyway: the pane degrades to its state
     * fields rather than travelling. A pack can make a facet name a kilobyte long, and eight of those
     * would be a packet large enough to disconnect the player it describes.
     */
    @Test
    void aProfileWhoseAuthoredTextExceedsTheBudgetDegradesInsteadOfTravelling() {
        Component huge = Component.literal("x".repeat(4096));
        List<ReputationNetwork.FacetSummary> fat = new ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_SYNCED_FACET_DETAILS; i++) {
            fat.add(new ReputationNetwork.FacetSummary(
                    ResourceLocation.fromNamespaceAndPath("mcareputation", "facet_" + i), huge, Optional.of(huge), 40,
                    -100, 100, 2, 0, true));
        }
        ReputationNetwork.ProfileSummary oversize = new ReputationNetwork.ProfileSummary(
                ProfileAvailability.AVAILABLE, ProfileCoverage.PARTIAL_LEGACY, false,
                Optional.empty(), 500, "renowned", Component.literal("Renowned"), 9,
                List.of(huge, huge, huge), fat, 4L, 2L);

        RegistryFriendlyByteBuf buf = buffer();
        ReputationNetwork.SelectedDetail.write(buf,
                detailWith(Optional.of(oversize), Optional.empty()));
        ReputationNetwork.SelectedDetail decoded = ReputationNetwork.SelectedDetail.read(buf);
        ReputationNetwork.ProfileSummary read = decoded.profile().orElseThrow();

        assertTrue(read.dominantTraits().isEmpty(), "the variable-length halves are dropped");
        assertTrue(read.details().isEmpty());
        assertEquals(Optional.of(ReputationNetwork.ProfileSummary.OVERSIZE_REASON), read.reason(),
                "and the pane says why, rather than looking like an empty profile");
        assertEquals(ProfileAvailability.AVAILABLE, read.availability(),
                "the availability and coverage are the part worth keeping");
        assertEquals(ProfileCoverage.PARTIAL_LEGACY, read.coverage());
        assertEquals(500, read.recognition());
        assertEquals(0, buf.readableBytes());
    }

    /**
     * The request stamp survives both ways, which is what lets a client drop a late reply (§18.3).
     * Zero is the reserved "nobody asked" value and must stay distinguishable from a real stamp.
     */
    @Test
    void theRequestStampIsEchoedOnTheReply() {
        for (int stamp : new int[] {0, 1, 77, 1 << 24}) {
            RegistryFriendlyByteBuf request = buffer();
            ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.encode(request,
                    new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty(), 0, stamp));
            assertEquals(stamp, ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC
                    .decode(request).requestId());
            assertEquals(0, request.readableBytes());

            RegistryFriendlyByteBuf reply = buffer();
            ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(reply,
                    new ReputationNetwork.SnapshotS2C(List.of(), Optional.empty(), List.of(), 0, 1,
                            0, stamp));
            assertEquals(stamp, ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(reply).requestId());
            assertEquals(0, reply.readableBytes());
        }
    }

    /** A hostile negative stamp cannot survive the decode, in either direction. */
    @Test
    void aNegativeRequestStampIsClampedOnArrival() {
        RegistryFriendlyByteBuf request = buffer();
        ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.encode(request,
                new ReputationNetwork.RequestSnapshotC2S(0, Optional.empty(), 0, -3));
        assertEquals(0,
                ReputationNetwork.RequestSnapshotC2S.STREAM_CODEC.decode(request).requestId());

        RegistryFriendlyByteBuf reply = buffer();
        ReputationNetwork.SnapshotS2C.STREAM_CODEC.encode(reply,
                new ReputationNetwork.SnapshotS2C(List.of(), Optional.empty(), List.of(), 0, 1, 0,
                        -9));
        assertEquals(ReputationNetwork.SnapshotS2C.UNSOLICITED,
                ReputationNetwork.SnapshotS2C.STREAM_CODEC.decode(reply).requestId());
    }

    /** A forged facet value cannot reach a layout as something that would draw off the panel. */
    @Test
    void forgedFacetNumbersAreClampedToWhatCanBeDrawn() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(TestFixtures.FACET);
        writeComponent(buf, Component.literal("Bravery"));
        buf.writeBoolean(false); // label: absent, in this branch's one-boolean optional framing
        buf.writeInt(Integer.MAX_VALUE);
        buf.writeInt(Integer.MIN_VALUE);
        buf.writeInt(Integer.MAX_VALUE);
        buf.writeVarInt(Integer.MAX_VALUE);
        buf.writeVarInt(-1);
        buf.writeBoolean(true);

        ReputationNetwork.FacetSummary decoded = ReputationNetwork.FacetSummary.read(buf);
        assertEquals(100, decoded.value());
        assertEquals(-100, decoded.rangeMin());
        assertEquals(100, decoded.rangeMax());
        assertEquals(ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY, decoded.supportingEvidence());
        assertEquals(0, decoded.opposingEvidence());
        assertFalse(decoded.label().isPresent());
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
