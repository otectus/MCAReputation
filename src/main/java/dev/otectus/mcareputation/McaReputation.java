package dev.otectus.mcareputation;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * MCA: Reputation — the suite's public memory and civic consequence layer.
 *
 * <p>Hearts remain MCA Reborn's visible individual-affection economy; MCA: Conversations owns private
 * interpersonal nuance; MCA: Quests owns structured work. This mod owns exactly one thing: a player's
 * <b>public standing with a community</b>, and the structured history of deeds that explains it.
 *
 * <p>This class holds only identity and logging so it can be referenced from the pure domain without
 * dragging in mod lifecycle. The Forge entry point is {@link McaReputationMod}.
 */
public final class McaReputation {

    public static final String MOD_ID = "mcareputation";

    /** The id MCA: Quests has used since its own reputation system shipped; several paths alias it. */
    public static final String QUESTS_MOD_ID = "mcaquests";

    /** MCA: Conversations' mod id, used only for optional-presence checks. */
    public static final String CONVERSATIONS_MOD_ID = "mcaconversations";

    /** MCA: Crime's mod id, used only for optional-presence checks and integration gating. */
    public static final String CRIME_MOD_ID = "mcacrime";

    /** MCA Reborn's mod id. */
    public static final String MCA_MOD_ID = "mca";

    public static final Logger LOGGER = LogUtils.getLogger();

    private McaReputation() {
    }

    /** A {@link ResourceLocation} in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** A {@link ResourceLocation} in MCA: Quests' namespace, for the compatibility aliases. */
    public static ResourceLocation questsId(String path) {
        return ResourceLocation.fromNamespaceAndPath(QUESTS_MOD_ID, path);
    }
}
