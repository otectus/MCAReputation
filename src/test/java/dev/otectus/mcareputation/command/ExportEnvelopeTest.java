package dev.otectus.mcareputation.command;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.PlayerReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mcareputation export} without a server: the envelope and the NBT to JSON conversion are
 * both pure, and this is the half of the command that can be wrong silently — a file that writes but
 * cannot be read back is indistinguishable from a good one until somebody needs it.
 */
class ExportEnvelopeTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;

    private static CompoundTag populatedStore() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        player.setLastKnownName("Ada");
        CommunityReputationRecord home = player.getOrCreate(TestFixtures.OVERWORLD_3);
        home.addBaseline(45, MIN, MAX);
        player.getOrCreate(TestFixtures.NETHER_3).addBaseline(-15, MIN, MAX);
        data.setDecayImmune(TestFixtures.OVERWORLD_3, true);
        return data.save(new CompoundTag());
    }

    /**
     * What goes out comes back: an exported store re-read through JSON loads to the same standing.
     *
     * <p>Compared through {@link ReputationSavedData#load} rather than by tag equality because JSON
     * carries no integer width — DFU brings {@code 45} back as a byte, not an int, so the tags differ
     * while the data does not. Every reader of this store goes through the numeric-tolerant NBT
     * getters, so that narrowing costs nothing; asserting raw tag equality would only assert DFU's
     * choice of the narrowest type.
     */
    @Test
    void anExportedStoreLoadsBackWithTheSameStanding() {
        CompoundTag tag = populatedStore();
        Tag reparsed = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE,
                NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
        assertTrue(reparsed instanceof CompoundTag, "a store is a compound on both sides of JSON");

        ReputationSavedData loaded = ReputationSavedData.load((CompoundTag) reparsed);
        assertEquals(45, loaded.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
        assertEquals(-15, loaded.score(TestFixtures.PLAYER_A, TestFixtures.NETHER_3));
        assertEquals("Ada", loaded.player(TestFixtures.PLAYER_A).orElseThrow().lastKnownName());
        assertTrue(loaded.isDecayImmune(TestFixtures.OVERWORLD_3));
        assertEquals(ReputationSavedData.FORMAT_VERSION, loaded.loadedVersion());
    }

    @Test
    void theEnvelopeNamesTheFormatVersionAndCarriesTheStore() {
        CompoundTag tag = populatedStore();
        JsonObject envelope = ReputationCommand.exportEnvelope(tag, "1.2.3", "2026-01-01T00:00:00Z");

        assertEquals(ReputationSavedData.FORMAT_VERSION, envelope.get("format").getAsInt());
        assertEquals("1.2.3", envelope.get("mod_version").getAsString(),
                "the version is passed in, never read from a literal in the export code");
        assertEquals("2026-01-01T00:00:00Z", envelope.get("exported_at").getAsString());
        assertTrue(envelope.get("data").isJsonObject());

        Tag data = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, envelope.get("data"));
        assertEquals(45, ReputationSavedData.load((CompoundTag) data)
                        .score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3),
                "the body of the envelope is the store, unaltered");
    }

    /** An empty store still exports: an operator asking on a fresh world gets a readable file. */
    @Test
    void anEmptyStoreExportsAsAnEmptyPlayerMap() {
        CompoundTag tag = ReputationSavedData.createForTest().save(new CompoundTag());
        JsonObject envelope = ReputationCommand.exportEnvelope(tag, "0.0.0", "2026-01-01T00:00:00Z");
        assertTrue(envelope.getAsJsonObject("data").getAsJsonObject("players").keySet().isEmpty());
    }
}
