package dev.otectus.mcareputation.profile;

import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.credit.CreditPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The social meaning of one accepted deed, <b>frozen at acceptance</b> (spec §9.4).
 *
 * <p>This is evidence attached to one incident, not a second profile database and not a cache. §9.4 is
 * explicit that a datapack edit changes <em>future</em> deeds and never rewrites a quantity a player
 * already earned, which means the authored numbers, the credit decision, the lifetimes and the
 * resolution multipliers that applied to this deed all have to be copied here and never looked up
 * again. Labels, colours and observer interpretation weights stay in {@link FacetDefinition} and may
 * change on reload; the numbers below may not.
 *
 * <h2>Why the origin marker matters more than it looks</h2>
 *
 * <p>Four situations produce very different payloads and must never be confused (§19.1):
 *
 * <ul>
 *   <li>{@link Origin#LIVE} — the deed was accepted while the profile content was published and the
 *       feature was on. Its quantities are real.</li>
 *   <li>{@link Origin#LEGACY_UNENRICHED} — the deed predates profiles. It is a <em>stub</em>: it
 *       carries no quantities at all and is a candidate for the §19.2 enrichment pass. Reading a
 *       missing field as a fully configured profile is exactly the mistake this marker prevents.</li>
 *   <li>{@link Origin#LEGACY_ENRICHED} — the enrichment pass has since established what could be
 *       established from the frozen migration manifest, at 100% historical credit because the old
 *       system stored no repeat-credit decision.</li>
 *   <li>{@link Origin#DISABLED_AT_OCCURRENCE} — the deed happened while profiles were switched off.
 *       It is <b>not</b> an upgrade candidate: nothing was observed about it that a later pass could
 *       honestly reconstruct.</li>
 * </ul>
 *
 * <h2>Bounds before allocation</h2>
 *
 * <p>{@link #load} validates every declared count, length and magnitude <em>before</em> it allocates
 * anything, and returns empty rather than throwing. §9.6 requires the NBT path to enforce the same
 * hard limits as the datapack codec — a malformed save must not be able to smuggle a larger authored
 * value into the active model — and §19.4 requires a malformed payload to be quarantined without
 * discarding the otherwise valid scalar incident that carries it.
 *
 * <p>Immutable. Aging replaces the whole payload rather than editing a field, so a staged supersession
 * can roll back to the previous instance by reference (§11.3).
 */
public record IncidentProfileEvidence(
        int schemaVersion,
        Origin origin,
        ResourceLocation profileId,
        long ruleFingerprint,
        long contentGeneration,
        Optional<Channel> recognition,
        List<Channel> facets,
        CreditDecision credit,
        long profileRevision) {

    /** The payload layout this build writes. Read paths accept nothing else. */
    public static final int SCHEMA_VERSION = 1;

    /** One recognition channel plus §10.5's eight facet entries. */
    public static final int MAX_CHANNELS = 1 + IncidentProfileDefinition.MAX_FACET_ENTRIES;

    /**
     * The largest magnitude any single channel may carry, in subunits: §9.6's 100 authored points.
     * Checked on load, because "authored values are bounded" is only true if the loader says so.
     */
    public static final long MAX_CHANNEL_SUBUNITS =
            ProfileMath.subunits(ProfileMath.MAX_FACET_POINTS);

    /** Structural ceiling on a stored credit ordinal; trackers saturate far below it. */
    public static final int MAX_CREDIT_ORDINAL = 1_000_000;

    /** Structural ceiling on a stored resource-id string, checked before it is parsed. */
    public static final int MAX_ID_LENGTH = 256;

    // --- NBT keys, short because every retained incident may carry one -------

    private static final String TAG_SCHEMA = "v";
    private static final String TAG_ORIGIN = "origin";
    private static final String TAG_PROFILE = "profile";
    private static final String TAG_FINGERPRINT = "fp";
    private static final String TAG_GENERATION = "gen";
    private static final String TAG_REVISION = "rev";
    private static final String TAG_CHANNEL_COUNT = "n";
    private static final String TAG_CHANNELS = "ch";
    private static final String TAG_CREDIT = "credit";

    private static final String TAG_FACET = "f";
    private static final String TAG_AUTHORED = "a";
    private static final String TAG_CREDITED = "c";
    private static final String TAG_CURRENT = "u";
    private static final String TAG_LIFETIME = "lt";
    private static final String TAG_STEP = "st";
    private static final String TAG_MODE = "mode";
    private static final String TAG_RESOLUTION = "rbp";

    private static final String TAG_GROUP = "g";
    private static final String TAG_ROLE = "r";
    private static final String TAG_GROUP_ORDINAL = "go";
    private static final String TAG_SUBJECT_ORDINAL = "so";
    private static final String TAG_GROUP_BP = "gbp";
    private static final String TAG_SUBJECT_BP = "sbp";
    private static final String TAG_EFFECTIVE_BP = "ebp";
    private static final String TAG_REASON = "why";

    private static final String TAG_APOLOGIZED = "ap";
    private static final String TAG_ATONED = "at";
    private static final String TAG_FORGIVEN = "fo";
    private static final String TAG_DISPROVEN = "di";

    /** Where this payload came from (§9.4 "Schema/origin", §19.1). */
    public enum Origin {

        /** Accepted with published profile content and the feature enabled. */
        LIVE,

        /** A deed that predates profiles, not yet examined by the §19.2 enrichment pass. */
        LEGACY_UNENRICHED,

        /** A pre-upgrade deed the frozen migration manifest could speak for, at 100% credit. */
        LEGACY_ENRICHED,

        /** Accepted while profiles were switched off. Never an upgrade candidate (§19.1). */
        DISABLED_AT_OCCURRENCE;

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Origin> byName(String name) {
            if (name == null || name.isEmpty()) {
                return Optional.empty();
            }
            for (Origin origin : values()) {
                if (origin.jsonName().equals(name.toLowerCase(Locale.ROOT))) {
                    return Optional.of(origin);
                }
            }
            return Optional.empty();
        }

        /** Whether a payload of this origin is allowed to carry quantities at all. */
        public boolean carriesQuantities() {
            return this == LIVE || this == LEGACY_ENRICHED;
        }

        /** Whether the §19.2 pass may still upgrade a payload of this origin. */
        public boolean isEnrichmentCandidate() {
            return this == LEGACY_UNENRICHED;
        }
    }

    /**
     * One frozen contribution: recognition, or one facet.
     *
     * @param facet       the facet this evidences, or empty for the recognition channel
     * @param authored    what the profile authored, in subunits, before repeat credit
     * @param credited    what repeat credit left of it; magnitude never above {@code authored}
     * @param current     what it is worth at the incident's profile clock right now
     * @param lifetime    the frozen finite linear lifetime, in ticks
     * @param decayStep   the frozen age quantization step, in ticks
     * @param mode        §12.1's resolution mode, frozen for this deed
     * @param resolution  the frozen monotonic multipliers an evaluative channel settles at
     */
    public record Channel(
            Optional<ResourceLocation> facet,
            long authored,
            long credited,
            long current,
            long lifetime,
            long decayStep,
            IncidentProfileDefinition.ResolutionMode mode,
            IncidentProfileDefinition.ResolutionMultipliers resolution) {

        public Channel {
            facet = facet == null ? Optional.<ResourceLocation>empty() : facet;
            mode = mode == null ? IncidentProfileDefinition.ResolutionMode.HISTORICAL : mode;
            resolution = resolution == null
                    ? IncidentProfileDefinition.ResolutionMultipliers.DEFAULT
                    : resolution;
        }

        /** Whether this is the recognition channel rather than a facet. */
        public boolean isRecognition() {
            return facet.isEmpty();
        }

        /** The channel name diagnostics print: {@code recognition} or the facet id. */
        public String channelName() {
            return facet.map(ResourceLocation::toString).orElse("recognition");
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            facet.ifPresent(id -> tag.putString(TAG_FACET, id.toString()));
            tag.putLong(TAG_AUTHORED, authored);
            tag.putLong(TAG_CREDITED, credited);
            tag.putLong(TAG_CURRENT, current);
            tag.putLong(TAG_LIFETIME, lifetime);
            tag.putLong(TAG_STEP, decayStep);
            tag.putString(TAG_MODE, mode.jsonName());
            CompoundTag bp = new CompoundTag();
            bp.putInt(TAG_APOLOGIZED, resolution.apologizedBp());
            bp.putInt(TAG_ATONED, resolution.atonedBp());
            bp.putInt(TAG_FORGIVEN, resolution.forgivenBp());
            bp.putInt(TAG_DISPROVEN, resolution.disprovenBp());
            tag.put(TAG_RESOLUTION, bp);
            return tag;
        }

        /**
         * One channel, or empty when anything about it is out of bounds. Every check below happens
         * before the {@link Channel} is constructed, and none of them can throw.
         */
        static Optional<Channel> load(CompoundTag tag) {
            if (tag == null) {
                return Optional.empty();
            }
            Optional<ResourceLocation> facet;
            if (tag.contains(TAG_FACET, Tag.TAG_STRING)) {
                String raw = tag.getString(TAG_FACET);
                if (raw.isEmpty() || raw.length() > MAX_ID_LENGTH) {
                    return Optional.empty();
                }
                ResourceLocation parsed = ResourceLocation.tryParse(raw);
                if (parsed == null) {
                    return Optional.empty();
                }
                facet = Optional.of(parsed);
            } else {
                facet = Optional.empty();
            }

            long authored = tag.getLong(TAG_AUTHORED);
            long credited = tag.getLong(TAG_CREDITED);
            long current = tag.getLong(TAG_CURRENT);
            if (!withinChannelBound(authored) || !withinChannelBound(credited)
                    || !withinChannelBound(current)) {
                return Optional.empty();
            }
            // Recognition is non-negative by definition (§7.1): becoming infamous makes you better
            // known, not less known, so a negative stored recognition is a corrupt payload.
            if (facet.isEmpty() && (authored < 0L || credited < 0L || current < 0L)) {
                return Optional.empty();
            }
            // Credit only ever reduces magnitude, and aging only ever reduces it further. Either
            // relation inverted means the file is claiming a deed grew after it was accepted.
            if (Math.abs(credited) > Math.abs(authored) || Math.abs(current) > Math.abs(credited)) {
                return Optional.empty();
            }
            if (!sameSignOrZero(authored, credited) || !sameSignOrZero(credited, current)) {
                return Optional.empty();
            }

            long lifetime = tag.getLong(TAG_LIFETIME);
            long step = tag.getLong(TAG_STEP);
            if (lifetime < ProfileMath.MIN_LIFETIME_TICKS || lifetime > ProfileMath.MAX_LIFETIME_TICKS) {
                return Optional.empty();
            }
            if (step < ProfileMath.MIN_DECAY_STEP_TICKS || step > ProfileMath.MAX_DECAY_STEP_TICKS
                    || step > lifetime) {
                return Optional.empty();
            }

            Optional<IncidentProfileDefinition.ResolutionMode> mode =
                    resolutionMode(tag.getString(TAG_MODE));
            if (mode.isEmpty()) {
                return Optional.empty();
            }
            Optional<IncidentProfileDefinition.ResolutionMultipliers> resolution =
                    multipliers(tag.getCompound(TAG_RESOLUTION));
            if (resolution.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Channel(facet, authored, credited, current, lifetime, step,
                    mode.get(), resolution.get()));
        }
    }

    public IncidentProfileEvidence {
        origin = origin == null ? Origin.LEGACY_UNENRICHED : origin;
        recognition = recognition == null ? Optional.<Channel>empty() : recognition;
        credit = credit == null
                ? CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY)
                : credit;
        profileRevision = Math.max(0L, profileRevision);
        // Facet channels are stored in id order and deduplicated: the payload feeds the dominance
        // tiebreak and the golden fixture, so two callers that supply the same facets in different
        // orders must produce byte-identical evidence.
        List<Channel> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (facets != null) {
            List<Channel> copy = new ArrayList<>(facets);
            copy.sort((a, b) -> a.channelName().compareTo(b.channelName()));
            for (Channel channel : copy) {
                if (channel == null || channel.isRecognition()) {
                    continue;
                }
                if (seen.add(channel.channelName())
                        && ordered.size() < IncidentProfileDefinition.MAX_FACET_ENTRIES) {
                    ordered.add(channel);
                }
            }
        }
        facets = List.copyOf(ordered);
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * A stub for a deed that predates profiles (§19.1). No quantities: it records only that this
     * incident is of a kind that <em>has</em> social meaning and that nothing has been established
     * about it yet, which is what makes it a candidate for the §19.2 pass instead of a zero.
     */
    public static IncidentProfileEvidence legacyStub(ResourceLocation profileId) {
        return new IncidentProfileEvidence(SCHEMA_VERSION, Origin.LEGACY_UNENRICHED, profileId, 0L, 0L,
                Optional.empty(), List.of(),
                CreditDecision.unlimited(CreditDecision.Reason.LEGACY_FULL_CREDIT), 0L);
    }

    /**
     * The payload for a deed accepted while profiles were switched off (§19.1, §20). The credit
     * decision is kept because repeat accounting is separate from profile display: switching profiles
     * off must not quietly restore unlimited positive standing.
     */
    public static IncidentProfileEvidence disabledAtOccurrence(ResourceLocation profileId,
                                                               CreditDecision credit) {
        return new IncidentProfileEvidence(SCHEMA_VERSION, Origin.DISABLED_AT_OCCURRENCE, profileId, 0L,
                0L, Optional.empty(), List.of(), credit, 0L);
    }

    /** A payload carrying quantities: {@link Origin#LIVE} or {@link Origin#LEGACY_ENRICHED}. */
    public static IncidentProfileEvidence of(Origin origin, ResourceLocation profileId,
                                             long ruleFingerprint, long contentGeneration,
                                             Optional<Channel> recognition, List<Channel> facets,
                                             CreditDecision credit) {
        return new IncidentProfileEvidence(SCHEMA_VERSION, origin, profileId, ruleFingerprint,
                contentGeneration, recognition, facets, credit, 1L);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Every channel, recognition first, then facets in id order. */
    public List<Channel> channels() {
        List<Channel> all = new ArrayList<>(1 + facets.size());
        recognition.ifPresent(all::add);
        all.addAll(facets);
        return List.copyOf(all);
    }

    public Optional<Channel> facet(ResourceLocation id) {
        if (id == null) {
            return Optional.empty();
        }
        for (Channel channel : facets) {
            if (channel.facet().filter(id::equals).isPresent()) {
                return Optional.of(channel);
            }
        }
        return Optional.empty();
    }

    /** Whether this payload carries any non-zero current contribution. */
    public boolean hasCurrentSubunits() {
        for (Channel channel : channels()) {
            if (channel.current() != 0L) {
                return true;
            }
        }
        return false;
    }

    /** Whether the §19.2 pass may still upgrade this payload. */
    public boolean isEnrichmentCandidate() {
        return origin.isEnrichmentCandidate();
    }

    /** The same payload with a bumped profile revision (§9.4 "Revision"). */
    public IncidentProfileEvidence withRevision(long revision) {
        return new IncidentProfileEvidence(schemaVersion, origin, profileId, ruleFingerprint,
                contentGeneration, recognition, facets, credit, revision);
    }

    // ------------------------------------------------------------------
    // Rule identity
    // ------------------------------------------------------------------

    /**
     * A stable digest of the authored quantities this payload froze (§9.4 "Rule identity").
     *
     * <p>FNV-1a over a canonical rendering rather than {@code hashCode}: the value is written to disk
     * and shown in diagnostics, so it has to mean the same thing on every JVM and in every future
     * build that can still read the file. It identifies the <em>rule</em>, not the payload — two deeds
     * accepted under one unchanged profile share a fingerprint, and an edited profile gives the next
     * deed a different one, which is precisely the question "was this judged by the same rule?".
     */
    public static long fingerprint(ResourceLocation profileId, IncidentProfileDefinition definition) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(profileId == null ? "?" : profileId.toString());
        if (definition != null) {
            canonical.append('|').append(definition.creditClass().jsonName())
                    .append('|').append(definition.majorEvidence())
                    .append('|').append(definition.effectiveDecayStepTicks())
                    .append('|').append(definition.creditPolicy()
                            .map(ResourceLocation::toString).orElse("-"));
            for (Map.Entry<ResourceLocation, IncidentProfileDefinition.Contribution> entry
                    : definition.allContributions().entrySet()) {
                IncidentProfileDefinition.Contribution contribution = entry.getValue();
                canonical.append('|')
                        .append(entry.getKey() == null ? "recognition" : entry.getKey().toString())
                        .append('=').append(contribution.points())
                        .append(':').append(contribution.lifetimeTicks())
                        .append(':').append(contribution.resolutionMode().jsonName())
                        .append(':').append(contribution.resolutionBp().apologizedBp())
                        .append(',').append(contribution.resolutionBp().atonedBp())
                        .append(',').append(contribution.resolutionBp().forgivenBp())
                        .append(',').append(contribution.resolutionBp().disprovenBp());
            }
        }
        return fnv1a64(canonical.toString());
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i) & 0xffff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_SCHEMA, schemaVersion);
        tag.putString(TAG_ORIGIN, origin.jsonName());
        tag.putString(TAG_PROFILE, profileId == null ? "" : profileId.toString());
        if (ruleFingerprint != 0L) {
            tag.putLong(TAG_FINGERPRINT, ruleFingerprint);
        }
        if (contentGeneration != 0L) {
            tag.putLong(TAG_GENERATION, contentGeneration);
        }
        if (profileRevision != 0L) {
            tag.putLong(TAG_REVISION, profileRevision);
        }
        List<Channel> all = channels();
        // The declared count is written beside the list and cross-checked on load. A length a reader
        // trusts without checking is how a hostile file drives an allocation.
        tag.putInt(TAG_CHANNEL_COUNT, all.size());
        if (!all.isEmpty()) {
            ListTag list = new ListTag();
            all.forEach(channel -> list.add(channel.save()));
            tag.put(TAG_CHANNELS, list);
        }
        tag.put(TAG_CREDIT, saveCredit(credit));
        return tag;
    }

    /**
     * One payload, or empty when anything about it is unusable.
     *
     * <p>Never throws, and never allocates a collection sized by the file: the declared channel count
     * is range-checked and matched against the stored list before a single channel is read (§19.4).
     * An empty result means the caller must quarantine the raw tag and keep the scalar incident — the
     * deed happened either way, and a missing payload makes profile coverage incomplete rather than
     * proving there is no adverse evidence (§19.4, I08).
     */
    public static Optional<IncidentProfileEvidence> load(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Optional.empty();
        }
        if (tag.getInt(TAG_SCHEMA) != SCHEMA_VERSION) {
            return Optional.empty();
        }
        Optional<Origin> origin = Origin.byName(tag.getString(TAG_ORIGIN));
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        String rawProfile = tag.getString(TAG_PROFILE);
        if (rawProfile.isEmpty() || rawProfile.length() > MAX_ID_LENGTH) {
            return Optional.empty();
        }
        ResourceLocation profileId = ResourceLocation.tryParse(rawProfile);
        if (profileId == null) {
            return Optional.empty();
        }
        long revision = tag.getLong(TAG_REVISION);
        long generation = tag.getLong(TAG_GENERATION);
        if (revision < 0L || generation < 0L) {
            return Optional.empty();
        }

        // --- the bound checks, all of them before any allocation ------------
        int declared = tag.getInt(TAG_CHANNEL_COUNT);
        if (declared < 0 || declared > MAX_CHANNELS) {
            return Optional.empty();
        }
        ListTag list = tag.getList(TAG_CHANNELS, Tag.TAG_COMPOUND);
        if (list.size() != declared) {
            return Optional.empty();
        }
        // An origin that carries no quantities may not smuggle any: a stub with channels would be
        // read by the enrichment pass as un-enriched and by everything else as evidence.
        if (!origin.get().carriesQuantities() && declared != 0) {
            return Optional.empty();
        }

        Optional<CreditDecision> credit = loadCredit(tag.getCompound(TAG_CREDIT));
        if (credit.isEmpty()) {
            return Optional.empty();
        }

        Optional<Channel> recognition = Optional.empty();
        List<Channel> facets = new ArrayList<>(declared);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < declared; i++) {
            Optional<Channel> channel = Channel.load(list.getCompound(i));
            if (channel.isEmpty()) {
                return Optional.empty();
            }
            Channel loaded = channel.get();
            if (!seen.add(loaded.channelName())) {
                return Optional.empty();
            }
            if (loaded.isRecognition()) {
                if (recognition.isPresent()) {
                    return Optional.empty();
                }
                recognition = channel;
            } else {
                facets.add(loaded);
            }
        }
        if (facets.size() > IncidentProfileDefinition.MAX_FACET_ENTRIES) {
            return Optional.empty();
        }
        return Optional.of(new IncidentProfileEvidence(SCHEMA_VERSION, origin.get(), profileId,
                tag.getLong(TAG_FINGERPRINT), generation, recognition, facets, credit.get(), revision));
    }

    private static CompoundTag saveCredit(CreditDecision decision) {
        CompoundTag tag = new CompoundTag();
        decision.group().ifPresent(group -> tag.putString(TAG_GROUP, group.toString()));
        decision.subjectRole().ifPresent(role -> tag.putString(TAG_ROLE, role));
        if (decision.groupOrdinal() != 0) {
            tag.putInt(TAG_GROUP_ORDINAL, decision.groupOrdinal());
        }
        if (decision.subjectOrdinal() != 0) {
            tag.putInt(TAG_SUBJECT_ORDINAL, decision.subjectOrdinal());
        }
        tag.putInt(TAG_GROUP_BP, decision.groupBp());
        tag.putInt(TAG_SUBJECT_BP, decision.subjectBp());
        tag.putInt(TAG_EFFECTIVE_BP, decision.effectiveBp());
        tag.putString(TAG_REASON, decision.reason().jsonName());
        return tag;
    }

    /**
     * The frozen credit decision. An unknown reason is <b>rejected</b> rather than defaulted: the
     * reason is the explanation for a number a player can see, and quietly reading an unrecognised one
     * as "full credit" would invent a story about a deed nobody can check (§9.6).
     */
    private static Optional<CreditDecision> loadCredit(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Optional.empty();
        }
        Optional<ResourceLocation> group = Optional.empty();
        if (tag.contains(TAG_GROUP, Tag.TAG_STRING)) {
            String raw = tag.getString(TAG_GROUP);
            if (raw.isEmpty() || raw.length() > MAX_ID_LENGTH) {
                return Optional.empty();
            }
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed == null) {
                return Optional.empty();
            }
            group = Optional.of(parsed);
        }
        Optional<String> role = Optional.empty();
        if (tag.contains(TAG_ROLE, Tag.TAG_STRING)) {
            String raw = tag.getString(TAG_ROLE);
            if (raw.isEmpty() || raw.length() > CreditPolicy.SubjectLimit.MAX_ROLE_LENGTH) {
                return Optional.empty();
            }
            role = Optional.of(raw);
        }
        int groupOrdinal = tag.getInt(TAG_GROUP_ORDINAL);
        int subjectOrdinal = tag.getInt(TAG_SUBJECT_ORDINAL);
        if (groupOrdinal < 0 || groupOrdinal > MAX_CREDIT_ORDINAL
                || subjectOrdinal < 0 || subjectOrdinal > MAX_CREDIT_ORDINAL) {
            return Optional.empty();
        }
        int groupBp = tag.getInt(TAG_GROUP_BP);
        int subjectBp = tag.getInt(TAG_SUBJECT_BP);
        int effectiveBp = tag.getInt(TAG_EFFECTIVE_BP);
        if (!withinBp(groupBp) || !withinBp(subjectBp) || !withinBp(effectiveBp)) {
            return Optional.empty();
        }
        // The minimum rule of §10.2 is structural, so a file claiming a higher effective percentage
        // than either ceiling is claiming credit no policy could have granted.
        if (effectiveBp > Math.min(groupBp, subjectBp)) {
            return Optional.empty();
        }
        Optional<CreditDecision.Reason> reason = creditReason(tag.getString(TAG_REASON));
        if (reason.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CreditDecision(group, role, groupOrdinal, subjectOrdinal, groupBp,
                subjectBp, effectiveBp, reason.get()));
    }

    private static Optional<CreditDecision.Reason> creditReason(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        for (CreditDecision.Reason reason : CreditDecision.Reason.values()) {
            if (reason.jsonName().equals(name.toLowerCase(Locale.ROOT))) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    private static Optional<IncidentProfileDefinition.ResolutionMode> resolutionMode(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        for (IncidentProfileDefinition.ResolutionMode mode
                : IncidentProfileDefinition.ResolutionMode.values()) {
            if (mode.jsonName().equals(name.toLowerCase(Locale.ROOT))) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /**
     * The frozen evaluative multipliers. §12.1's monotonicity is enforced here as well as in the
     * codec: a stored progression that <em>increases</em> would let a stronger settlement resurrect a
     * contribution a weaker one already reduced.
     */
    private static Optional<IncidentProfileDefinition.ResolutionMultipliers> multipliers(
            CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Optional.empty();
        }
        int apologized = tag.getInt(TAG_APOLOGIZED);
        int atoned = tag.getInt(TAG_ATONED);
        int forgiven = tag.getInt(TAG_FORGIVEN);
        int disproven = tag.getInt(TAG_DISPROVEN);
        if (!withinBp(apologized) || !withinBp(atoned) || !withinBp(forgiven) || !withinBp(disproven)) {
            return Optional.empty();
        }
        if (atoned > apologized || forgiven > atoned || disproven > forgiven || disproven != 0) {
            return Optional.empty();
        }
        return Optional.of(new IncidentProfileDefinition.ResolutionMultipliers(apologized, atoned,
                forgiven, disproven));
    }

    private static boolean withinBp(int value) {
        return value >= 0 && value <= ProfileMath.FULL_BP;
    }

    private static boolean withinChannelBound(long subunits) {
        return subunits >= -MAX_CHANNEL_SUBUNITS && subunits <= MAX_CHANNEL_SUBUNITS;
    }

    private static boolean sameSignOrZero(long outer, long inner) {
        if (inner == 0L || outer == 0L) {
            return true;
        }
        return (outer < 0L) == (inner < 0L);
    }
}
