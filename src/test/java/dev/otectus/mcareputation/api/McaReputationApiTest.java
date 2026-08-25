package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §23's integration toggles, wired for real: {@code enableQuestsIntegration} and
 * {@code enableConversationsIntegration} were documented for a full version while having zero call
 * sites, so switching them off did nothing. They now gate the API surface each companion uses.
 */
class McaReputationApiTest {

    @AfterEach
    void tearDown() {
        McaReputationConfig.TestOverrides.reset();
    }

    private static ReputationRequest requestFrom(net.minecraft.resources.ResourceLocation source) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3,
                TestFixtures.ASSAULT, source, Optional.empty(), OptionalInt.empty(), Optional.empty(),
                List.of(), Set.of(), Map.of(), 0L);
    }

    @Test
    void aDisabledQuestsIntegrationRefusesQuestsSourcedWrites() {
        McaReputationConfig.TestOverrides.questsIntegration = false;
        ReputationResult record = McaReputationApi.record(requestFrom(BuiltinIncidents.SOURCE_QUESTS));
        assertFalse(record.applied());
        assertEquals(ReputationResult.Reason.DISABLED, record.reason());

        ResolutionResult resolve = McaReputationApi.resolve(null, TestFixtures.PLAYER_A,
                TestFixtures.OVERWORLD_3, java.util.UUID.randomUUID(), IncidentStatus.ATONED,
                BuiltinIncidents.SOURCE_QUESTS);
        assertEquals(ResolutionResult.Reason.DISABLED, resolve.reason());
    }

    @Test
    void aDisabledConversationsIntegrationRefusesConversationsSourcedWrites() {
        McaReputationConfig.TestOverrides.conversationsIntegration = false;
        ReputationResult record =
                McaReputationApi.record(requestFrom(BuiltinIncidents.SOURCE_CONVERSATIONS));
        assertEquals(ReputationResult.Reason.DISABLED, record.reason());
    }

    @Test
    void aDisabledConversationsIntegrationServesNeutralReads() {
        McaReputationConfig.TestOverrides.conversationsIntegration = false;
        assertEquals(0, McaReputationApi.getCheckBias(null, TestFixtures.PLAYER_A,
                TestFixtures.OVERWORLD_3, "trust"), "authored disabled-context fallbacks must fire");
        assertFalse(McaReputationApi.matches(null, TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3,
                ReputationQuery.builder().minTier("friend").build()));
        assertTrue(McaReputationApi.gossipCandidate(null, TestFixtures.PLAYER_A,
                TestFixtures.OVERWORLD_3, java.util.UUID.randomUUID(), "Ada").isEmpty());
    }

    @Test
    void coreAndThirdPartySourcesAreNeverGatedByTheIntegrationToggles() {
        McaReputationConfig.TestOverrides.questsIntegration = false;
        McaReputationConfig.TestOverrides.conversationsIntegration = false;
        // A core-sourced request passes the toggle gate; with no server behind it, it fails later
        // with ERROR — the point is that the refusal is not DISABLED.
        ReputationResult record = McaReputationApi.record(requestFrom(BuiltinIncidents.SOURCE_CORE));
        assertFalse(record.applied());
        assertEquals(ReputationResult.Reason.ERROR, record.reason());
    }
}
