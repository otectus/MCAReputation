package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.SupersedeSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * A keyed delivery plus the two profile-specific things a producer may need to say about it (§9.5,
 * §14.2).
 *
 * <p>A wrapper rather than new fields on {@code ReputationRequest}, for the same reason
 * {@link IncidentDelivery} is a wrapper: that record is compiled against by companions from a pinned
 * jar, and moving its canonical constructor would break the mods this feature exists for.
 *
 * <p>{@link #profileSelection} answers §9.5: Quests may need different profile rules for a
 * humanitarian rescue, a crafting commission and a dangerous contract that all use one generic
 * completion incident. It selects an <b>authored</b> profile by id and nothing else — no numeric
 * override, no facet value, no credit percentage. The selection is validated against the incident
 * type's authored allowlist and is ignored, with a warning, when it is not permitted; ordinary
 * callers leave it empty and get the incident's own default profile. Nothing in a client packet may
 * ever supply one.
 *
 * <p>{@link #supersession} routes the delivery through the same profile-aware supersession path the
 * native assault-to-killing upgrade uses, so the pair totals the successor's figure instead of
 * stacking (§16.3's assault-to-killing parity).
 *
 * @see McaReputationApi#deliverProfiled(ProfiledDelivery)
 * @since MCA: Reputation 0.6.0
 */
public record ProfiledDelivery(IncidentDelivery delivery, Optional<ResourceLocation> profileSelection,
                               Optional<SupersedeSpec> supersession) {

    public ProfiledDelivery {
        if (delivery == null) {
            throw new IllegalArgumentException("ProfiledDelivery requires a delivery");
        }
        profileSelection = profileSelection == null ? Optional.empty() : profileSelection;
        supersession = supersession == null ? Optional.empty() : supersession;
    }

    /** The plain case: an existing delivery, the incident's own profile, no supersession. */
    public static ProfiledDelivery of(IncidentDelivery delivery) {
        return new ProfiledDelivery(delivery, Optional.empty(), Optional.empty());
    }

    public static ProfiledDelivery of(IncidentDelivery delivery, ResourceLocation profileSelection) {
        return new ProfiledDelivery(delivery, Optional.ofNullable(profileSelection), Optional.empty());
    }

    public static ProfiledDelivery superseding(IncidentDelivery delivery, SupersedeSpec spec) {
        return new ProfiledDelivery(delivery, Optional.empty(), Optional.ofNullable(spec));
    }

    /** Whether this delivery carries an operation identity, and therefore earns a receipt. */
    public boolean keyed() {
        return delivery.keyed();
    }
}
