package dev.otectus.mcareputation.fixture;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.IncidentQuery;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.OpinionResult;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReputationCapabilities;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A consumer written against the <b>NeoForge 0.5.0</b> API and run against the current
 * implementation (§21.2's binary-linkage fixture).
 *
 * <p>The baseline is this branch's own 0.5.0 commit, not the Forge release the 0.6.0 feature set was
 * ported from: a Forge-era api jar does not link here at all, because the {@code api.event} types
 * extend {@code net.neoforged.bus.api.Event}. That difference is the whole reason
 * {@code API_VERSION} is 2 on this branch and 1 on Forge, and it is also why a Forge baseline would
 * fail this fixture for a reason that says nothing about 0.6.0.
 *
 * <p>Compiled against the frozen 0.5.0 API jar, so it can only name types and methods that release
 * actually had; then executed with the new implementation classes on the classpath, so every call
 * site has to link. A record whose component list moved, a method whose parameters were widened, an
 * enum constant that was renamed — none of those are compile errors in this project, and all of them
 * are {@code NoSuchMethodError} or {@code NoSuchFieldError} here.
 *
 * <p>Deliberately server-free. Every entry point is documented to degrade rather than throw, so the
 * calls below pass {@code null} where a server would be and assert only that they return: the
 * subject of this fixture is linkage, not behaviour, and behaviour has its own tests.
 */
public final class LegacyApiConsumer {

    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("examplemod", "quests");
    private static final CommunityKey COMMUNITY =
            new CommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private LegacyApiConsumer() {
    }

    public static void main(String[] args) {
        int checks = 0;

        require(McaReputationApi.getApiVersion() == 2,
                "API_VERSION must stay 2 on this branch: the NeoForge companions hard-refuse "
                        + "anything else, and 0.6.0 is additive");
        checks++;

        McaReputationApi.isEnabled();
        checks++;

        ReputationCapabilities capabilities = McaReputationApi.capabilities(null);
        require(capabilities.apiVersion() == 2, "the capability snapshot must report version 2");
        require(capabilities.has(ReputationCapabilities.FEATURE_DELIVERY),
                "FEATURE_DELIVERY was advertised by 0.5.0 and must not be withdrawn");
        require(capabilities.has(ReputationCapabilities.FEATURE_RECEIPTS),
                "FEATURE_RECEIPTS was advertised by 0.5.0 and must not be withdrawn");
        require(capabilities.has(ReputationCapabilities.FEATURE_GOSSIP_STORY),
                "FEATURE_GOSSIP_STORY was advertised by 0.5.0 and must not be withdrawn");
        require(capabilities.nativeKinds() != null && capabilities.claimants() != null,
                "the capability record's component list must not have moved");
        checks += 5;

        require(!McaReputationApi.getScore(null, PLAYER, COMMUNITY).isPresent(),
                "an unresolvable read degrades to empty rather than throwing");
        require(McaReputationApi.getScoreOrZero(null, PLAYER, COMMUNITY) == 0, "zero for no server");
        require(McaReputationApi.getSnapshot(null, PLAYER, COMMUNITY).isEmpty(), "no snapshot");
        require(McaReputationApi.getAllSnapshots(null, PLAYER).isEmpty(), "no snapshots");
        require(McaReputationApi.knownCommunities(null, PLAYER).isEmpty(), "no communities");
        require(McaReputationApi.getTierId(null, PLAYER, COMMUNITY) != null, "a tier id is returned");
        checks += 6;

        ReputationQuery query = ReputationQuery.builder().min(10).minTier("friend").build();
        require(!McaReputationApi.matches(null, PLAYER, COMMUNITY, query),
                "an authored standing gate fails closed with no server");
        require(McaReputationApi.recentIncidents(null, PLAYER, COMMUNITY, 5).isEmpty(), "no history");
        require(McaReputationApi.selectIncidents(null, PLAYER, COMMUNITY,
                IncidentQuery.builder().newestOnly(true).build()).isEmpty(), "no selection");
        require(McaReputationApi.selectIncidents(null, PLAYER, COMMUNITY,
                IncidentQuery.builder().knownToSpeaker(true).build(),
                SpeakerContext.of(UUID.randomUUID(), true)).isEmpty(), "no speaker selection");
        checks += 4;

        OpinionResult opinion = McaReputationApi.getVillagerOpinionDetailed(null, PLAYER,
                UUID.randomUUID(), COMMUNITY);
        require(opinion.availability() != null, "an opinion always reports its availability");
        require(McaReputationApi.getOpinionBias(null, PLAYER, UUID.randomUUID(), COMMUNITY,
                "trust") == 0, "an unavailable opinion contributes no bias");
        require(McaReputationApi.findReceipt(null, "examplemod", PLAYER, COMMUNITY, "op-1").isEmpty(),
                "no receipt without a store");
        require(!McaReputationApi.receiptFloor(null, PLAYER).isPresent(), "no receipt floor");
        checks += 4;

        ReputationRequest request = new ReputationRequest(null, PLAYER, COMMUNITY,
                ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_assaulted"), SOURCE,
                Optional.of("op-1"), OptionalInt.empty(), Optional.empty(), List.of(), Set.of(),
                Map.of(), 0L);
        ReputationResult recorded = McaReputationApi.record(request);
        require(!recorded.applied(), "a write with no server is refused, not applied");
        DeliveryOutcome delivered = McaReputationApi.deliver(
                IncidentDelivery.of(request, "examplemod", "op-1"));
        require(delivered.outcome() == ReceiptOutcome.REFUSED_INVALID
                        || delivered.outcome() == ReceiptOutcome.REFUSED_DISABLED,
                "a contained failure is a typed refusal");
        require(delivered.receipt().isEmpty(), "a retryable refusal stores no receipt");
        ReputationResult superseded = McaReputationApi.recordSuperseding(request,
                SupersedeSpec.of(UUID.randomUUID(), 1200L, true));
        require(!superseded.applied(), "the supersede path refuses the same way");
        checks += 4;

        StandingChange change = new StandingChange(PLAYER, COMMUNITY, 0, 5, "stranger",
                "acquaintance", ResourceLocation.fromNamespaceAndPath("mcareputation", "default"), null, 1L,
                ChangeCause.DEED, false);
        require(change.delta() == 5, "StandingChange's component list must not have moved");
        require(change.scoreChanged() && change.tierChanged(), "and its derived answers must hold");
        require(ReceiptOutcome.APPLIED.isTerminal(), "APPLIED is still terminal");
        require(ChangeCause.DECAY.isQuiet(), "DECAY is still quiet");
        checks += 4;

        System.out.println("apiLinkage: " + checks + " 0.5.0 call sites linked against the current "
                + "implementation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("0.5.0 linkage broken: " + message);
        }
    }
}
