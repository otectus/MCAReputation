package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every sentence the standing screen says about a public profile (spec §18.1, §18.2).
 *
 * <h2>Why these are static and here rather than in the screen</h2>
 *
 * <p>The interesting behaviour of this pane is what it says when it cannot say much: a genuine
 * stranger, a temporarily unreadable answer, an imported history with holes in it, a migration still
 * running, and a read-only store are five different facts, and §18.2 requires them to read
 * differently. Those are exactly the cases nobody exercises by hand. Pure methods over the decoded
 * packet make all five checkable with no client running, which is the same reason
 * {@code ReputationScreen.opinionLine} is static.
 *
 * <h2>No colour-only meaning (§28.4)</h2>
 *
 * <p>Nothing here returns a colour. A facet's direction is carried by the pack's own word for it
 * ("Brave", "Cruel") and by an explicit "in your favour" / "against you" phrase, and a value is
 * carried by a signed number. The screen draws all of it in the same two neutral greys the rest of
 * the panel uses, so a player who cannot distinguish the greys loses nothing.
 */
final class ProfilePresentation {

    private ProfilePresentation() {
    }

    /**
     * Whether this pane is worth drawing at all.
     *
     * <p>A switched-off or unpublished feature draws nothing: the operator turned it off, and a line
     * explaining that to every player on every screen would be clutter about a decision they cannot
     * change. Everything else — including a failure — has something honest to say.
     */
    static boolean drawable(Optional<ReputationNetwork.ProfileSummary> profile) {
        if (profile.isEmpty()) {
            return false;
        }
        ProfileAvailability availability = profile.get().availability();
        return availability != ProfileAvailability.DISABLED
                && availability != ProfileAvailability.UNSUPPORTED;
    }

    /**
     * How widely known the player is here, in the village's own words.
     *
     * <p>Present even at zero recognition, because "nobody here would know your face" is an answer
     * the recognition ladder authors deliberately (§7.2's floor tier) — the absence of the line would
     * be the only ambiguous state left.
     */
    static Optional<Component> recognitionLine(ReputationNetwork.ProfileSummary profile,
                                               boolean showRecognition, boolean showExactValues) {
        if (!showRecognition || profile.availability() != ProfileAvailability.AVAILABLE) {
            return Optional.empty();
        }
        if (showExactValues) {
            return Optional.of(Component.translatable("mcareputation.screen.profile.recognition_exact",
                    profile.recognitionTierName(), profile.recognition(),
                    profile.recognitionEvidence()));
        }
        return Optional.of(Component.translatable("mcareputation.screen.profile.recognition",
                profile.recognitionTierName()));
    }

    /**
     * The traits the village would describe the player by, or nothing when it has earned none.
     *
     * <p>An empty list is not a gap to fill: §8.2's label rules mean the village has evidence but not
     * enough of it to speak of, and inventing a reassuring phrase would be the same overclaim in the
     * other direction.
     */
    static Optional<Component> knownForLine(ReputationNetwork.ProfileSummary profile,
                                            boolean showKnownFor) {
        if (!showKnownFor || profile.availability() != ProfileAvailability.AVAILABLE
                || profile.dominantTraits().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable("mcareputation.screen.profile.known_for",
                join(profile.dominantTraits())));
    }

    /**
     * The one honest state line, or nothing when the answer is complete and ordinary.
     *
     * <p>Ordered by how much it changes what the player should believe: an unreadable answer first,
     * then a store that is not being written to, then a history with holes, then a migration in
     * progress, and only then the genuine "nobody knows you here". Exactly one of them is shown, so
     * the pane never stacks caveats.
     */
    static Optional<Component> stateLine(ReputationNetwork.ProfileSummary profile) {
        if (profile.availability() != ProfileAvailability.AVAILABLE) {
            return Optional.of(Component.translatable("mcareputation.screen.profile.unavailable"));
        }
        if (profile.readOnlyStore()) {
            return Optional.of(Component.translatable("mcareputation.screen.profile.read_only"));
        }
        if (profile.coverage() == ProfileCoverage.MIGRATING) {
            return Optional.of(Component.translatable("mcareputation.screen.profile.migrating"));
        }
        if (profile.coverage() == ProfileCoverage.PARTIAL_LEGACY) {
            // "Recognition history is incomplete", never "nobody knows you" (§18.2): a missing import
            // hides evidence, and reading the gap as an absence is wrong in exactly the direction
            // that flatters a stranger.
            return Optional.of(Component.translatable("mcareputation.screen.profile.partial_legacy"));
        }
        if (profile.recognition() == 0 && profile.dominantTraits().isEmpty()
                && profile.details().isEmpty()) {
            return Optional.of(Component.translatable("mcareputation.screen.profile.unknown"));
        }
        return Optional.empty();
    }

    /** Whether there is anything behind the details expansion, so the button can be left out. */
    static boolean hasDetails(Optional<ReputationNetwork.ProfileSummary> profile) {
        return profile.map(summary -> summary.availability() == ProfileAvailability.AVAILABLE
                && !summary.details().isEmpty()).orElse(false);
    }

    /** The expansion's own label, which states what it will do rather than only that it is a toggle. */
    static Component detailsToggle(boolean expanded) {
        return Component.translatable(expanded
                ? "mcareputation.screen.profile.hide_details"
                : "mcareputation.screen.profile.show_details");
    }

    /**
     * One facet's line: the pack's name for it and the word this value earns.
     *
     * <p>A zero value is "known both ways" rather than either label. For a unipolar facet zero is not
     * the opposite trait at all (§8.1), and for a bipolar one it is a genuine balance of evidence —
     * neither is describable by a label, and both are worth saying.
     */
    static Component facetLine(ReputationNetwork.FacetSummary facet) {
        return facet.label()
                .map(label -> Component.translatable("mcareputation.screen.profile.facet",
                        facet.name(), label))
                .orElseGet(() -> Component.translatable("mcareputation.screen.profile.facet_balanced",
                        facet.name()));
    }

    /**
     * The meta line under a facet: which way it leans, how much evidence is behind it, whether it is
     * enough to be spoken of, and — only when asked for — the numbers.
     */
    static Component facetMeta(ReputationNetwork.FacetSummary facet, boolean showExactValues) {
        MutableComponent meta = Component.translatable(direction(facet.value())).copy();
        meta = append(meta, Component.translatable("mcareputation.screen.profile.facet_evidence",
                facet.supportingEvidence(), facet.opposingEvidence()));
        if (!facet.labelEligible()) {
            meta = append(meta,
                    Component.translatable("mcareputation.screen.profile.facet_not_spoken"));
        }
        if (showExactValues) {
            meta = append(meta, Component.translatable("mcareputation.screen.profile.facet_value",
                    ReputationScreen.signed(facet.value()),
                    facet.value() < 0 ? facet.rangeMin() : facet.rangeMax()));
        }
        return meta;
    }

    /** Which way a value leans, as words: the sign is never left to colour alone (§28.4). */
    private static String direction(int value) {
        if (value > 0) {
            return "mcareputation.screen.profile.direction.favour";
        }
        if (value < 0) {
            return "mcareputation.screen.profile.direction.against";
        }
        return "mcareputation.screen.profile.direction.balanced";
    }

    // ------------------------------------------------------------------
    // The observer's own view (§13.2, §13.4)
    // ------------------------------------------------------------------

    /**
     * What the villager in front of the player knows them for.
     *
     * <p>A villager who knows nothing gets a line of their own. That is the answer §13.3 protects: a
     * valid zero must never be quietly replaced by the village's own view, and the only way a player
     * can tell the difference is if the screen says so.
     */
    static Optional<Component> observerLine(Optional<ReputationNetwork.VillagerProfileSummary> observer,
                                            boolean show) {
        if (!show || observer.isEmpty()) {
            return Optional.empty();
        }
        ReputationNetwork.VillagerProfileSummary view = observer.get();
        List<Component> traits = view.known().dominantTraits();
        if (traits.isEmpty()) {
            return Optional.of(view.knownIncidents() == 0
                    ? Component.translatable("mcareputation.screen.profile.observer_nothing",
                            view.villagerName())
                    : Component.translatable("mcareputation.screen.profile.observer_unspoken",
                            view.villagerName()));
        }
        return Optional.of(Component.translatable("mcareputation.screen.profile.observer",
                view.villagerName(), join(traits)));
    }

    /**
     * §13.4's bounded explanation, in one line: how much of the player's history this villager
     * actually knows, and how they came to know it.
     *
     * <p>No informant is ever named. Hearsay records that a story reached this villager, not who told
     * it, and §13.4 is explicit that current knowledge alone does not prove a source.
     */
    static Optional<Component> observerKnowledgeLine(
            Optional<ReputationNetwork.VillagerProfileSummary> observer, boolean show) {
        if (!show || observer.isEmpty() || observer.get().knownIncidents() == 0) {
            return Optional.empty();
        }
        ReputationNetwork.VillagerProfileSummary view = observer.get();
        return Optional.of(Component.translatable("mcareputation.screen.profile.observer_knowledge",
                view.knownIncidents(), view.involvedCount(), view.witnessedCount(),
                view.hearsayCount()));
    }

    /**
     * What this villager's own reading of those traits is worth, and on what basis.
     *
     * <p>Only shown when the numbers were asked for: it is the one line where an operator's cap
     * becomes visible, and a player who does not want numbers does not need it. A zero adjustment is
     * still worth saying when interpretation is switched off or could not be resolved, because
     * "nothing moved it" and "nothing was read" are different.
     */
    static Optional<Component> observerAdjustmentLine(
            Optional<ReputationNetwork.VillagerProfileSummary> observer, boolean show,
            boolean showExactValues) {
        if (!show || !showExactValues || observer.isEmpty()) {
            return Optional.empty();
        }
        ReputationNetwork.VillagerProfileSummary view = observer.get();
        return Optional.of(Component.translatable("mcareputation.screen.profile.observer_adjustment",
                ReputationScreen.signed(view.facetAdjustment()),
                Component.translatable(basisKey(view.traitBasis()))));
    }

    /**
     * How the observer's interpretation weights were arrived at, spelled out rather than derived from
     * the enum name, so {@code LangParityTest} sees all three keys.
     */
    static String basisKey(VillagerProfileSnapshot.TraitBasis basis) {
        return switch (basis) {
            case RESOLVED -> "mcareputation.screen.profile.basis.resolved";
            case DISABLED -> "mcareputation.screen.profile.basis.disabled";
            default -> "mcareputation.screen.profile.basis.neutral";
        };
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Traits arrive already resolved and already ordered (§8.2); the client only joins them. */
    private static Component join(List<Component> parts) {
        MutableComponent joined = Component.empty();
        List<Component> all = new ArrayList<>(parts);
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(" · "));
            }
            joined.append(all.get(i));
        }
        return joined;
    }

    private static MutableComponent append(MutableComponent meta, Component part) {
        return meta.append(Component.literal(" · ")).append(part);
    }
}
