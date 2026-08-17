package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * A narrowly scoped sink for canonical state, so an add-on can keep a usable fallback copy (spec
 * §25.1, §32.3).
 *
 * <p>MCA: Quests registers one. When Reputation is installed, its store is canonical and Quests
 * routes every read and write through the bridge — but a player may later remove Reputation, and
 * §32.6 requires Quests to keep working with sensible standing rather than resetting everyone to
 * zero. The mirror is how it stays in step.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Called <b>after</b> the canonical commit has succeeded, never before or during. A mirror can
 *       therefore never influence, veto, or corrupt the canonical outcome.</li>
 *   <li>Called on the server thread.</li>
 *   <li><b>Must not call back into Reputation.</b> A mirror that records a score by asking Reputation
 *       to record a score would recurse; §32.3 states the rule and the service does not defend
 *       against a violation beyond containing the exception.</li>
 *   <li>Must not fire gameplay events or send player notifications — the canonical commit already
 *       did (§32.3). A mirror is bookkeeping, not a second source of feedback.</li>
 *   <li>May throw. Every call is wrapped in {@code catch (Throwable)} and logged; a broken mirror
 *       degrades to a stale fallback copy, never to a failed transaction (§18).</li>
 * </ul>
 *
 * <p>Only scalar standing, tier, and title state is mirrored. Incident history stays owned by
 * Reputation and is deliberately not duplicated (§10).
 */
public interface ReputationMirror {

    /**
     * A player's standing with one community changed.
     *
     * @param highWaterTierId the highest tier ever reached on this ladder, or {@code null} if none
     */
    void mirrorScore(UUID player, CommunityKey community, int score,
                     ResourceLocation ladder, String highWaterTierId);

    /** A community-scoped title was granted. */
    void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title);

    /** A global title was granted. */
    void mirrorGlobalTitle(UUID player, ResourceLocation title);

    /** A human-readable name for this mirror, used only in the log line when it throws. */
    default String mirrorName() {
        return getClass().getSimpleName();
    }
}
