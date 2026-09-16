package dev.otectus.mcareputation.fixture;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.CoreIncidentAuthority;
import dev.otectus.mcareputation.api.CoreIncidentKind;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.IncidentQuery;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.OpinionResult;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.api.ReputationCapabilities;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationProfileChangedEvent;
import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCapabilities;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.ProfileCreditExplanation;
import dev.otectus.mcareputation.api.profile.ProfileQuery;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.api.profile.ProfiledDelivery;
import dev.otectus.mcareputation.api.profile.ProfiledDeliveryResult;
import dev.otectus.mcareputation.api.profile.RecognitionValue;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * An outside consumer, compiled against the produced API jar <b>alone</b> plus the ordinary
 * NeoForge/Minecraft compile environment (§21.2).
 *
 * <p>This is the only check that actually proves signature closure. {@code verifyApiJar} asserts
 * which entries are in the artifact and that it carries no resources; it cannot notice that a method
 * this mod exports mentions a type it does not, because the whole implementation is on the classpath
 * when this project compiles itself. Here it is not — so a new signature that names an internal
 * tracker, policy or mutable record fails this compile instead of failing in somebody's build.
 *
 * <p>Nothing here runs. Every statement exists to force a symbol to resolve, so the file should read
 * as a list of things a companion is allowed to depend on: the whole 0.5.0 surface, and the profile
 * surface added in 0.6.0.
 */
public final class ApiJarConsumer {

    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("examplemod", "quests");
    private static final ResourceLocation FACET = ResourceLocation.fromNamespaceAndPath("mcareputation", "reliability");

    private ApiJarConsumer() {
    }

    /** The 0.5.0 surface a companion written against the previous release already uses. */
    static void legacySurface(MinecraftServer server, UUID player, CommunityKey community) {
        int version = McaReputationApi.getApiVersion();
        boolean enabled = McaReputationApi.isEnabled();
        OptionalInt score = McaReputationApi.getScore(server, player, community);
        int orZero = McaReputationApi.getScoreOrZero(server, player, community);
        String tier = McaReputationApi.getTierId(server, player, community);
        Optional<ReputationSnapshot> snapshot = McaReputationApi.getSnapshot(server, player, community);
        List<ReputationSnapshot> all = McaReputationApi.getAllSnapshots(server, player);
        List<CommunityKey> known = McaReputationApi.knownCommunities(server, player);
        boolean matches = McaReputationApi.matches(server, player, community,
                ReputationQuery.builder().min(10).minTier("friend").build());
        List<ReputationIncidentView> recent = McaReputationApi.recentIncidents(server, player, community, 5);
        List<ReputationIncidentView> selected = McaReputationApi.selectIncidents(server, player,
                community, IncidentQuery.builder().tag("crime").newestOnly(true).build());
        OpinionResult opinion = McaReputationApi.getVillagerOpinionDetailed(server, player, player,
                community);
        Optional<SpeakerContext> speaker = Optional.of(SpeakerContext.of(player, true));
        Optional<ReceiptView> receipt = McaReputationApi.findReceipt(server, "examplemod", player,
                community, "op-1");
        ReputationCapabilities capabilities = McaReputationApi.capabilities(server);
        boolean hasDelivery = capabilities.has(ReputationCapabilities.FEATURE_DELIVERY);
        ReputationRequest request = new ReputationRequest(server, player, community,
                ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_assaulted"), SOURCE,
                Optional.of("op-1"), OptionalInt.empty(), Optional.empty(), List.of(), Set.of(),
                Map.of(), 0L);
        ReputationResult result = McaReputationApi.record(request);
        DeliveryOutcome delivered = McaReputationApi.deliver(
                IncidentDelivery.of(request, "examplemod", "op-1"));
        ReceiptOutcome outcome = delivered.outcome();
        boolean terminal = outcome.isTerminal();
        ReputationResult superseded = McaReputationApi.recordSuperseding(request,
                SupersedeSpec.of(UUID.randomUUID(), 1200L, true));
        StandingChange change = new StandingChange(player, community, 0, 5, "stranger",
                "acquaintance", ResourceLocation.fromNamespaceAndPath("mcareputation", "default"), null, 1L,
                ChangeCause.DEED, false);
        int delta = change.delta();
        ChangeCause cause = ChangeCause.DECAY;
        boolean quiet = cause.isQuiet();
    }

    /** The 0.6.0 profile surface. Every type here must be reachable from the API jar alone. */
    static void profileSurface(MinecraftServer server, UUID player, CommunityKey community) {
        ProfileCapabilities capabilities = McaReputationApi.profileCapabilities(server);
        boolean ready = capabilities.ready();
        boolean advertised = capabilities.has(ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT);
        List<ResourceLocation> facets = capabilities.publishedFacets();
        ProfileCoverage coverage = capabilities.coverage();

        ProfileQueryResult<ProfileSnapshot> detailed =
                McaReputationApi.getProfileDetailed(server, player, community);
        ProfileAvailability availability = detailed.availability();
        Optional<String> reason = detailed.reason();
        if (detailed.isAvailable()) {
            ProfileSnapshot profile = detailed.value().orElseThrow();
            RecognitionValue recognition = profile.recognition();
            int value = recognition.value();
            String recognitionTier = recognition.tierId();
            boolean observed = recognition.observed();
            List<FacetValue> values = profile.facets();
            List<ResourceLocation> dominant = profile.dominantFacets();
            Optional<FacetValue> reliability = profile.facet(FACET);
            long profileRevision = profile.profileRevision();
            long generation = profile.definitionGeneration();
            ProfileCoverage history = profile.coverage();
        }

        ProfileQueryResult<ProfileSnapshot> stored =
                McaReputationApi.inspectStoredProfile(server, player, community);
        ProfileQueryResult<VillagerProfileSnapshot> byId =
                McaReputationApi.getVillagerProfileDetailed(server, player, player, community);
        if (byId.isAvailable()) {
            VillagerProfileSnapshot view = byId.value().orElseThrow();
            int base = view.baseOpinion();
            int adjustment = view.facetAdjustment();
            int finalOpinion = view.finalOpinion();
            VillagerProfileSnapshot.TraitBasis basis = view.traitBasis();
            boolean knows = view.knowsAnything();
            ProfileSnapshot knownProfile = view.knownProfile();
        }

        ProfileQuery query = ProfileQuery.builder()
                .minRecognition(15)
                .minRecognitionTier("recognized")
                .facet(FACET, 20, 2)
                .facet(ProfileQuery.FacetPredicate.atMost(FACET, 40))
                .allowPartialHistory(false)
                .build();
        boolean valid = query.valid();
        boolean coverageSensitive = query.dependsOnCompleteHistory();
        ProfileQueryResult<Boolean> gate = McaReputationApi.matchesProfile(server, player, community,
                query);
        boolean passed = gate.orElse(false);
        ProfileQueryResult<Boolean> speakerGate = McaReputationApi.matchesSpeakerProfile(server, player,
                community, SpeakerContext.of(player, true), query);
    }

    /** The profiled delivery wrapper and its detailed outcome. */
    static void profiledDelivery(MinecraftServer server, UUID player, CommunityKey community) {
        ReputationRequest request = new ReputationRequest(server, player, community,
                ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_assaulted"), SOURCE,
                Optional.of("op-2"), OptionalInt.empty(), Optional.empty(), List.of(), Set.of(),
                Map.of(), 0L);
        ProfiledDelivery delivery = new ProfiledDelivery(
                IncidentDelivery.of(request, "examplemod", "op-2"),
                Optional.of(ResourceLocation.fromNamespaceAndPath("examplemod", "rescue_profile")),
                Optional.of(SupersedeSpec.of(UUID.randomUUID(), 1200L, true)));
        ProfiledDeliveryResult result = McaReputationApi.deliverProfiled(delivery);
        DeliveryOutcome outcome = result.outcome();
        ProfileAvailability availability = result.profileAvailability();
        Optional<ResourceLocation> applied = result.appliedProfile();
        boolean recorded = result.profileEvidenceRecorded();
        if (result.credit().isPresent()) {
            ProfileCreditExplanation credit = result.credit().get();
            ProfileCreditExplanation.Reason reason = credit.reason();
            int bp = credit.effectiveBasisPoints();
            boolean full = credit.fullCredit();
        }
    }

    /** The events a consumer subscribes to, including the new profile envelope. */
    static void listeners(ReputationChangedEvent standing, ReputationProfileChangedEvent profile) {
        int oldScore = standing.oldScore();
        ChangeCause standingCause = standing.cause();
        UUID player = profile.playerId();
        CommunityKey community = profile.community();
        int recognitionDelta = profile.recognitionDelta();
        List<ResourceLocation> changed = profile.changedFacets();
        long revision = profile.profileRevision();
        Optional<UUID> incident = profile.incidentId();
        Optional<String> operation = profile.operationKey();
        boolean quiet = profile.quiet();
    }

    /** The core-incident authority contract, including §16.3's per-kind declaration. */
    static CoreIncidentAuthority authority() {
        return new CoreIncidentAuthority() {

            @Override
            public ResourceLocation authorityId() {
                return ResourceLocation.fromNamespaceAndPath("examplemod", "crime");
            }

            @Override
            public boolean owns(CoreIncidentKind kind) {
                return declaredKinds().map(kinds -> kinds.contains(kind)).orElse(false);
            }

            @Override
            public Optional<Set<CoreIncidentKind>> declaredKinds() {
                return Optional.of(Set.of(CoreIncidentKind.MCA_VILLAGER_ASSAULT,
                        CoreIncidentKind.MCA_VILLAGER_KILL));
            }

            @Override
            public boolean canDeliver(CoreIncidentKind kind) {
                return true;
            }
        };
    }
}
