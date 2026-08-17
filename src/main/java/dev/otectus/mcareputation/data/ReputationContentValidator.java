package dev.otectus.mcareputation.data;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.ReputationTier;
import dev.otectus.mcareputation.reputation.ReputationTierSet;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.TitleDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Keeps the identifier rule in one place for tests. */
    static boolean isValidIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
