package dev.otectus.mcareputation.data;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-definition validation (spec §21.3), run over the freshly prepared maps before anything goes
 * live and again by {@code /mcareputation validate}.
 *
 * <p>The codecs already reject anything structurally wrong on its own — an out-of-range multiplier, a
 * ladder whose thresholds do not ascend, a private incident with a score. What is left for here is
 * everything that can only be judged with the <em>whole picture</em>: whether a tier's title actually
 * exists, whether a ladder's floor is low enough for the configured minimum score, whether shipped
 * content stays inside the limits shipped content is held to.
 *
 * <p>Problems carry a {@link Problem.Severity}. An {@code ERROR} names content that will not behave
 * as authored — strict mode refuses to swap the registries over it. A {@code WARNING} is advice about
 * content that works but smells; strict mode must not reject a working pack over advice, which is the
 * exact mistake the previous flat string list made possible.
 *
 * <p>Every message names the exact id and field, because a validation error that does not say which
 * file to open is barely better than silence.
 */
public final class ReputationContentValidator {

    /** One finding. {@code toString} is the loggable form. */
    public record Problem(Severity severity, String message) {

        public enum Severity { ERROR, WARNING }

        public static Problem error(String message) {
            return new Problem(Severity.ERROR, message);
        }

        public static Problem warning(String message) {
            return new Problem(Severity.WARNING, message);
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    /** §21.3: tags and context-variable names are bounded identifiers, not free prose. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]{1,64}");

    private ReputationContentValidator() {
    }

    public static List<Problem> validate(Map<ResourceLocation, IncidentDefinition> incidents,
                                         Map<ResourceLocation, ReputationTierSet> ladders,
                                         Map<ResourceLocation, TitleDefinition> titles) {
        List<Problem> problems = new ArrayList<>();
        validateLadders(ladders, titles, problems);
        validateIncidents(incidents, problems);
        validateTitleConflicts(titles, problems);
        validateDefaultLadderPresent(ladders, problems);
        return problems;
    }

    private static void validateLadders(Map<ResourceLocation, ReputationTierSet> ladders,
                                        Map<ResourceLocation, TitleDefinition> titles,
                                        List<Problem> problems) {
        int minimumScore = McaReputationConfig.minimumScore();
        ladders.forEach((ladderId, ladder) -> {
            if (ladder.isEmpty()) {
                problems.add(Problem.error("ladder " + ladderId + " defines no tiers"));
                return;
            }
            ReputationTier floor = ladder.tiers().get(0);
            // §21.3: the floor must sit at or below the configured minimum score, OR at or below zero.
            // The second clause is what makes the shipped ladder legal: its floor is -300 while the
            // minimum score is -1000, and scores below -300 simply rest in the floor tier. A floor
            // ABOVE zero is the real error — it would leave a brand-new player, at score 0, with no
            // tier to be in at all.
            if (floor.threshold() > 0 && floor.threshold() > minimumScore) {
                problems.add(Problem.error("ladder " + ladderId + " floor tier '" + floor.id()
                        + "' has threshold " + floor.threshold() + ", which is above both zero and the "
                        + "configured minimum score " + minimumScore
                        + "; a player at 0 would fall outside every tier"));
            }
            for (ReputationTier tier : ladder.tiers()) {
                // An error, not advice: the runtime hard-clamps to ±BIAS_SHIPPED_LIMIT, so an authored
                // value beyond it is a number the pack promises and the game will never deliver.
                if (!tier.withinShippedBiasLimit()) {
                    problems.add(Problem.error("ladder " + ladderId + " tier '" + tier.id()
                            + "' has a bias outside ±" + ReputationTier.BIAS_SHIPPED_LIMIT + " (trust "
                            + tier.trustBias() + ", respect " + tier.respectBias()
                            + "); the runtime clamps to the limit, so the authored value is a lie"));
                }
                // An unresolved title is only worth reporting when the ladder and the title share a
                // namespace. The shipped ladder deliberately grants `mcaquests:honored_of_village` to
                // preserve the id players already hold (§32.4); on a standalone install MCA: Quests is
                // not there to define it, and that is expected, not a pack error. Ownership does not
                // depend on a definition — an undefined title simply displays as its own name (§17.4).
                tier.grantsTitle().ifPresent(title -> {
                    if (!titles.containsKey(title) && title.getNamespace().equals(ladderId.getNamespace())) {
                        problems.add(Problem.warning("ladder " + ladderId + " tier '" + tier.id()
                                + "' grants_title " + title + ", which no loaded datapack defines even "
                                + "though it is in the same namespace as the ladder; the title will "
                                + "still be granted and will display as its id"));
                    }
                });
            }
        });
    }

    private static void validateIncidents(Map<ResourceLocation, IncidentDefinition> incidents,
                                          List<Problem> problems) {
        int minimumScore = McaReputationConfig.minimumScore();
        int maximumScore = McaReputationConfig.maximumScore();
        int span = Math.max(Math.abs(minimumScore), Math.abs(maximumScore));

        incidents.forEach((id, definition) -> {
            if (Math.abs(definition.defaultDelta()) > span) {
                problems.add(Problem.warning("incident " + id + " default_delta "
                        + definition.defaultDelta() + " exceeds the configured score range ±" + span
                        + "; it will be clamped"));
            }
            if (definition.effectiveMaxOverrideAbs() > span) {
                problems.add(Problem.warning("incident " + id + " max_override_abs "
                        + definition.effectiveMaxOverrideAbs()
                        + " exceeds the configured score range ±" + span));
            }
            if (definition.allowPrivateScore()) {
                problems.add(Problem.error("incident " + id + " sets allow_private_score, a "
                        + "development-only override; no shipped pack may use it"));
            }
            // §21.3: tags and gossip variables are bounded identifiers. A malformed one never crashes,
            // but it can silently never match a selector or always render as an empty string, which is
            // worse than failing loudly here.
            for (String tag : definition.tags()) {
                if (!IDENTIFIER.matcher(tag).matches()) {
                    problems.add(Problem.error("incident " + id + " tag '" + tag + "' is not a valid "
                            + "identifier (lowercase a-z, 0-9, '_', '.', '-', at most 64 chars); "
                            + "selectors filtering on it would never match"));
                }
            }
            for (String variable : definition.gossip().with()) {
                if (!IDENTIFIER.matcher(variable.toLowerCase(java.util.Locale.ROOT)).matches()) {
                    problems.add(Problem.error("incident " + id + " gossip.with variable '" + variable
                            + "' is not a valid identifier; it would always render as an empty string"));
                }
            }
            // A witnessed incident that neither scores nor is retained when unwitnessed can never do
            // anything at all — almost certainly an authoring mistake rather than an intent.
            if (definition.visibility() == IncidentVisibility.WITNESSED
                    && definition.defaultDelta() == 0
                    && !definition.retainUnwitnessed()
                    && !definition.gossip().isTellable()) {
                problems.add(Problem.warning("incident " + id + " is witnessed with no delta, no gossip "
                        + "phrase, and retain_unwitnessed false, so recording it can have no observable "
                        + "effect"));
            }
            if (definition.decay().decays() && definition.defaultDelta() == 0) {
                problems.add(Problem.warning("incident " + id + " declares a decay policy but has "
                        + "default_delta 0, so there is nothing to decay"));
            }
            if (definition.retentionTicks().isPresent() && definition.pinned()) {
                problems.add(Problem.warning("incident " + id + " is pinned and also sets "
                        + "retention_ticks; pinned incidents are never pruned, so the retention window "
                        + "has no effect"));
            }
        });
    }

    /**
     * §21.3: two loaded titles whose ids differ only by namespace almost certainly collide by
     * accident — they display identically in most UI, and a ladder grant naming the "wrong" one is
     * indistinguishable in game. Advice, not an error: the shipped compatibility aliases rely on
     * intentional cross-namespace duplication with identical definitions.
     */
    private static void validateTitleConflicts(Map<ResourceLocation, TitleDefinition> titles,
                                               List<Problem> problems) {
        Map<String, ResourceLocation> byPath = new HashMap<>();
        titles.forEach((id, definition) -> {
            ResourceLocation existing = byPath.putIfAbsent(id.getPath(), id);
            if (existing != null && !existing.equals(id)
                    && !titles.get(existing).equals(definition)) {
                problems.add(Problem.warning("titles " + existing + " and " + id + " share the path '"
                        + id.getPath() + "' with different definitions; a grant naming one of them is "
                        + "easy to mistake for the other"));
            }
        });
    }

    private static void validateDefaultLadderPresent(Map<ResourceLocation, ReputationTierSet> ladders,
                                                     List<Problem> problems) {
        if (!ladders.containsKey(ReputationTiers.DEFAULT_ID)
                && !ladders.containsKey(ReputationTiers.LEGACY_DEFAULT_ID)) {
            problems.add(Problem.warning("no default tier ladder is defined (" + ReputationTiers.DEFAULT_ID
                    + " or " + ReputationTiers.LEGACY_DEFAULT_ID
                    + "); the built-in ladder will be used instead"));
        }
    }

    // ------------------------------------------------------------------
    // Profile content (§9.6)
    // ------------------------------------------------------------------

    /** The four new registries as one candidate generation, before anything is published. */
    public record ProfileContent(
            Map<ResourceLocation, FacetDefinition> facets,
            Map<ResourceLocation, RecognitionTierSet> recognitionLadders,
            Map<ResourceLocation, IncidentProfileDefinition> profiles,
            Map<ResourceLocation, CreditPolicy> creditPolicies) {

        public static final ProfileContent EMPTY =
                new ProfileContent(Map.of(), Map.of(), Map.of(), Map.of());

        public ProfileContent {
            facets = facets == null ? Map.of() : Map.copyOf(facets);
            recognitionLadders = recognitionLadders == null ? Map.of() : Map.copyOf(recognitionLadders);
            profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
            creditPolicies = creditPolicies == null ? Map.of() : Map.copyOf(creditPolicies);
        }

        public boolean isEmpty() {
            return facets.isEmpty() && recognitionLadders.isEmpty() && profiles.isEmpty()
                    && creditPolicies.isEmpty();
        }
    }

    /**
     * What cross-registry validation found, and — crucially — <b>which ids must not be published</b>.
     *
     * <p>§9.6 asks for two different behaviours from one pass. In strict mode any error rejects the
     * whole generation. In lenient mode the working content has to survive: a crime definition whose
     * optional profile reference is broken keeps scoring, with only the new attachment disabled. That
     * is impossible to do from a list of log strings, so the rejected ids are returned explicitly and
     * the reload removes exactly those, cascading facet rejections into the profiles that reference
     * them so a half-consistent bundle can never reach {@link ProfileRegistryBundle}.
     */
    public record ProfileValidation(
            List<Problem> problems,
            Set<ResourceLocation> rejectedFacets,
            Set<ResourceLocation> rejectedRecognitionLadders,
            Set<ResourceLocation> rejectedProfiles,
            Set<ResourceLocation> rejectedCreditPolicies,
            Set<ResourceLocation> incidentsWithUnusableProfile) {

        public static final ProfileValidation CLEAN = new ProfileValidation(List.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of());

        public ProfileValidation {
            problems = problems == null ? List.of() : List.copyOf(problems);
            rejectedFacets = copyIds(rejectedFacets);
            rejectedRecognitionLadders = copyIds(rejectedRecognitionLadders);
            rejectedProfiles = copyIds(rejectedProfiles);
            rejectedCreditPolicies = copyIds(rejectedCreditPolicies);
            incidentsWithUnusableProfile = copyIds(incidentsWithUnusableProfile);
        }

        private static Set<ResourceLocation> copyIds(Set<ResourceLocation> ids) {
            return ids == null ? Set.of() : Set.copyOf(ids);
        }

        public boolean hasErrors() {
            return problems.stream().anyMatch(Problem::isError);
        }

        /** Whether anything at all has to be removed before the bundle is consistent. */
        public boolean requiresSanitisation() {
            return !rejectedFacets.isEmpty() || !rejectedRecognitionLadders.isEmpty()
                    || !rejectedProfiles.isEmpty() || !rejectedCreditPolicies.isEmpty()
                    || !incidentsWithUnusableProfile.isEmpty();
        }
    }

    /**
     * Validates the new profile registries against each other and against the incident definitions
     * that select them, applying every numeric hard bound §9.6 tabulates that a single codec cannot
     * see on its own.
     *
     * <p>The order matters: facets and credit policies are judged first, then profiles (which may be
     * rejected for referencing a rejected facet or policy), then incidents (which may lose their
     * attachment for referencing a rejected profile). Judging them in the other order would let a
     * profile survive its own broken dependency.
     */
    public static ProfileValidation validateProfileContent(
            Map<ResourceLocation, IncidentDefinition> incidents, ProfileContent content) {
        List<Problem> problems = new ArrayList<>();
        Set<ResourceLocation> rejectedFacets = new LinkedHashSet<>();
        Set<ResourceLocation> rejectedLadders = new LinkedHashSet<>();
        Set<ResourceLocation> rejectedProfiles = new LinkedHashSet<>();
        Set<ResourceLocation> rejectedPolicies = new LinkedHashSet<>();
        Set<ResourceLocation> unusableAttachments = new LinkedHashSet<>();

        validateFacets(content, problems, rejectedFacets);
        validateRecognitionLadders(content, problems, rejectedLadders);
        validateCreditPolicies(content, problems, rejectedPolicies);
        validateProfiles(content, incidents, rejectedFacets, rejectedPolicies, problems, rejectedProfiles);
        validateProfileAttachments(content, incidents, rejectedProfiles, problems, unusableAttachments);

        return new ProfileValidation(problems, rejectedFacets, rejectedLadders, rejectedProfiles,
                rejectedPolicies, unusableAttachments);
    }

    private static void validateFacets(ProfileContent content, List<Problem> problems,
                                       Set<ResourceLocation> rejected) {
        // Over the bound: reject the surplus in id order rather than the whole set, so which facets
        // survive is deterministic instead of depending on which files the pack happened to list.
        if (content.facets().size() > ProfileRegistryBundle.MAX_FACETS) {
            List<ResourceLocation> surplus = sortedIds(content.facets().keySet())
                    .subList(ProfileRegistryBundle.MAX_FACETS, content.facets().size());
            problems.add(Problem.error("too many facet definitions: " + content.facets().size()
                    + " loaded but at most " + ProfileRegistryBundle.MAX_FACETS
                    + " are supported; these will not be loaded: " + surplus));
            rejected.addAll(surplus);
        }
        for (ResourceLocation id : sortedIds(content.facets().keySet())) {
            FacetDefinition facet = content.facets().get(id);
            if (facet.opinionWeightBp() == 0 && facet.personalityOverrides().isEmpty()) {
                problems.add(Problem.warning("facet " + id + " has opinion_weight_bp 0 and no "
                        + "personality_overrides, so it describes the player but never influences any "
                        + "villager's opinion"));
            }
            if (facet.labelMinMagnitude() > facet.range().magnitude()) {
                problems.add(Problem.error("facet " + id + " label_min_magnitude "
                        + facet.labelMinMagnitude() + " exceeds its own range magnitude "
                        + facet.range().magnitude() + "; the label could never appear"));
                rejected.add(id);
            }
        }
    }

    private static void validateRecognitionLadders(ProfileContent content, List<Problem> problems,
                                                   Set<ResourceLocation> rejected) {
        if (content.recognitionLadders().size() > ProfileRegistryBundle.MAX_RECOGNITION_LADDERS) {
            List<ResourceLocation> surplus = sortedIds(content.recognitionLadders().keySet())
                    .subList(ProfileRegistryBundle.MAX_RECOGNITION_LADDERS,
                            content.recognitionLadders().size());
            problems.add(Problem.error("too many recognition ladders: "
                    + content.recognitionLadders().size() + " loaded but at most "
                    + ProfileRegistryBundle.MAX_RECOGNITION_LADDERS + " are supported; these will not "
                    + "be loaded: " + surplus));
            rejected.addAll(surplus);
        }
        for (ResourceLocation id : sortedIds(content.recognitionLadders().keySet())) {
            RecognitionTierSet ladder = content.recognitionLadders().get(id);
            // The codec enforces the zero floor and strict ascent; what it cannot see is a ladder
            // whose top rung is unreachable because recognition saturates below it.
            RecognitionTierSet.Tier top = ladder.tiers().get(ladder.size() - 1);
            if (top.threshold() > ProfileMath.MAX_RECOGNITION) {
                problems.add(Problem.error("recognition ladder " + id + " tier '" + top.id()
                        + "' has threshold " + top.threshold() + ", above the maximum recognition "
                        + ProfileMath.MAX_RECOGNITION + "; nobody could ever reach it"));
                rejected.add(id);
            }
        }
        if (!content.recognitionLadders().isEmpty()
                && !content.recognitionLadders().containsKey(RecognitionTierSet.DEFAULT_ID)) {
            problems.add(Problem.warning("no default recognition ladder is defined ("
                    + RecognitionTierSet.DEFAULT_ID + "); the built-in ladder will be used instead"));
        }
    }

    private static void validateCreditPolicies(ProfileContent content, List<Problem> problems,
                                               Set<ResourceLocation> rejected) {
        Map<ResourceLocation, ResourceLocation> firstFileForGroup = new HashMap<>();
        Map<ResourceLocation, CreditPolicy> firstPolicyForGroup = new HashMap<>();

        for (ResourceLocation id : sortedIds(content.creditPolicies().keySet())) {
            CreditPolicy policy = content.creditPolicies().get(id);
            ResourceLocation group = policy.group();
            if (!id.equals(group)) {
                problems.add(Problem.warning("credit policy " + id + " declares group " + group
                        + "; trackers are keyed by the group, so the file id is only documentation"));
            }
            ResourceLocation existingFile = firstFileForGroup.putIfAbsent(group, id);
            if (existingFile == null) {
                firstPolicyForGroup.put(group, policy);
                continue;
            }
            // §9.6: contradictory definitions sharing a group are rejected. Two identical files are
            // harmless (a pack and its compatibility alias), two different ones are ambiguous — and
            // guessing which wins would silently change how much farming a server permits.
            if (!firstPolicyForGroup.get(group).equals(policy)) {
                problems.add(Problem.error("credit policies " + existingFile + " and " + id
                        + " both define group " + group + " with different schedules; one group has one "
                        + "allowance and the definitions contradict each other"));
                rejected.add(existingFile);
                rejected.add(id);
            }
        }

        Set<ResourceLocation> groups = new LinkedHashSet<>(firstFileForGroup.keySet());
        if (groups.size() > ProfileRegistryBundle.MAX_CREDIT_GROUPS) {
            problems.add(Problem.error("too many credit groups in one content generation: "
                    + groups.size() + " but at most " + ProfileRegistryBundle.MAX_CREDIT_GROUPS
                    + " are supported"));
            rejected.addAll(content.creditPolicies().keySet());
        }

        for (ResourceLocation id : sortedIds(content.creditPolicies().keySet())) {
            CreditPolicy policy = content.creditPolicies().get(id);
            // §10.2: an honest description of what a nonzero tail is. Advice, not an error — it is a
            // legitimate pack-author choice, just not one that should be made by accident.
            if (policy.permitsIndefiniteCredit()) {
                problems.add(Problem.warning("credit policy " + id + " has tail_bp " + policy.tailBp()
                        + ", so repeating this deed forever keeps paying " + policy.tailBp() / 100
                        + "% of full credit; shipped policies use a zero tail"));
            }
            policy.subjectLimit().ifPresent(limit -> {
                if (!IDENTIFIER.matcher(limit.role()).matches()) {
                    problems.add(Problem.error("credit policy " + id + " subject_limit.role '"
                            + limit.role() + "' is not a valid identifier (lowercase a-z, 0-9, '_', "
                            + "'.', '-', at most 64 chars)"));
                    rejected.add(id);
                }
            });
        }
    }

    private static void validateProfiles(ProfileContent content,
                                         Map<ResourceLocation, IncidentDefinition> incidents,
                                         Set<ResourceLocation> rejectedFacets,
                                         Set<ResourceLocation> rejectedPolicies,
                                         List<Problem> problems,
                                         Set<ResourceLocation> rejected) {
        if (content.profiles().size() > ProfileRegistryBundle.MAX_PROFILES) {
            List<ResourceLocation> surplus = sortedIds(content.profiles().keySet())
                    .subList(ProfileRegistryBundle.MAX_PROFILES, content.profiles().size());
            problems.add(Problem.error("too many incident profiles: " + content.profiles().size()
                    + " loaded but at most " + ProfileRegistryBundle.MAX_PROFILES
                    + " are supported; these will not be loaded: " + surplus));
            rejected.addAll(surplus);
        }

        for (ResourceLocation id : sortedIds(content.profiles().keySet())) {
            IncidentProfileDefinition profile = content.profiles().get(id);

            profile.facets().forEach((facetId, contribution) -> {
                FacetDefinition facet = content.facets().get(facetId);
                if (facet == null || rejectedFacets.contains(facetId)) {
                    problems.add(Problem.error("incident profile " + id + " contributes to facet "
                            + facetId + ", which no loaded datapack defines; the profile cannot be "
                            + "used"));
                    rejected.add(id);
                    return;
                }
                // §9.6: an authored facet value must respect the facet's own range and sign. A +8
                // on a facet that tops out at +5 is a promise the read model will clamp away, and a
                // negative value on a unipolar facet is a claim the facet cannot express at all.
                if (!facet.range().permits(contribution.points())) {
                    problems.add(Problem.error("incident profile " + id + " contributes "
                            + contribution.points() + " to facet " + facetId + ", outside that "
                            + "facet's authored range " + facet.range().min() + ".."
                            + facet.range().max()));
                    rejected.add(id);
                }
            });

            profile.creditPolicy().ifPresent(policyId -> {
                if (!content.creditPolicies().containsKey(policyId)
                        || rejectedPolicies.contains(policyId)) {
                    problems.add(Problem.error("incident profile " + id + " names credit_policy "
                            + policyId + ", which no loaded datapack defines; without it the repeat "
                            + "limit the profile promises would not exist"));
                    rejected.add(id);
                }
            });

            // §7.3: recognition survives apology, atonement and forgiveness. An evaluative recognition
            // would fade the fact that people know who you are because you said sorry.
            profile.recognition().ifPresent(recognition -> {
                if (recognition.resolutionMode()
                        == IncidentProfileDefinition.ResolutionMode.EVALUATIVE) {
                    problems.add(Problem.warning("incident profile " + id + " authors recognition with "
                            + "resolution_mode 'evaluative'; recognition normally survives apology and "
                            + "atonement (§7.3), so 'recognition' is almost certainly meant"));
                }
            });

            for (ResourceLocation allowed : profile.allowedIncidents()) {
                if (!incidents.containsKey(allowed)) {
                    problems.add(Problem.warning("incident profile " + id + " allows incident "
                            + allowed + ", which no loaded datapack defines; the entry has no effect "
                            + "until that incident exists"));
                }
            }
        }
    }

    private static void validateProfileAttachments(ProfileContent content,
                                                   Map<ResourceLocation, IncidentDefinition> incidents,
                                                   Set<ResourceLocation> rejectedProfiles,
                                                   List<Problem> problems,
                                                   Set<ResourceLocation> unusable) {
        for (ResourceLocation id : sortedIds(incidents.keySet())) {
            IncidentDefinition definition = incidents.get(id);
            if (definition.socialProfile().isEmpty()) {
                continue;
            }
            ResourceLocation profileId = definition.socialProfile().get();
            IncidentProfileDefinition profile = content.profiles().get(profileId);
            if (profile == null || rejectedProfiles.contains(profileId)) {
                problems.add(Problem.error("incident " + id + " names social_profile " + profileId
                        + ", which is not available; the incident keeps working and contributes no "
                        + "profile evidence"));
                unusable.add(id);
                continue;
            }
            // §9.5: selection must be compatible with the authored source allowlist, or a generic
            // completion event could quietly borrow a specific heroic profile.
            if (!profile.permits(id)) {
                problems.add(Problem.error("incident " + id + " names social_profile " + profileId
                        + ", whose allowed_incidents does not list it"));
                unusable.add(id);
                continue;
            }
            // I03: a private deed has no public audience, so it can carry no public profile. Advice,
            // not an error: the reference is legal and simply contributes nothing (§13.1).
            if (definition.visibility() == IncidentVisibility.PRIVATE) {
                problems.add(Problem.warning("incident " + id + " is private and names social_profile "
                        + profileId + "; a private deed contributes no public recognition or facets"));
            }
        }
    }

    private static List<ResourceLocation> sortedIds(java.util.Collection<ResourceLocation> ids) {
        List<ResourceLocation> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparing(ResourceLocation::toString));
        return sorted;
    }

    /** Keeps the identifier rule in one place for tests. */
    static boolean isValidIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
