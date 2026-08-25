package dev.otectus.mcareputation.community;

import dev.otectus.mcareputation.TestFixtures;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 1. */
class CommunityKeyTest {

    @Test
    void sameDimensionAndIdAreEqual() {
        assertEquals(new CommunityKey(new ResourceLocation("minecraft:overworld"), 3),
                TestFixtures.OVERWORLD_3);
        assertEquals(TestFixtures.OVERWORLD_3.hashCode(),
                new CommunityKey(new ResourceLocation("minecraft:overworld"), 3).hashCode());
    }

    /**
     * The defect this whole type exists to prevent: MCA allocates village ids per dimension, so
     * village 3 in the Nether is not village 3 in the overworld.
     */
    @Test
    void sameIdInTwoDimensionsStaysDistinct() {
        assertNotEquals(TestFixtures.OVERWORLD_3, TestFixtures.NETHER_3);
        assertNotEquals(TestFixtures.OVERWORLD_3.asString(), TestFixtures.NETHER_3.asString());
    }

    @Test
    void nbtRoundTrip() {
        CompoundTag tag = TestFixtures.NETHER_3.save();
        assertEquals(Optional.of(TestFixtures.NETHER_3), CommunityKey.load(tag));
    }

    @Test
    void packetRoundTrip() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        TestFixtures.OVERWORLD_7.write(buf);
        assertEquals(TestFixtures.OVERWORLD_7, CommunityKey.read(buf));
        assertEquals(0, buf.readableBytes(), "the whole payload should have been consumed");
    }

    @Test
    void stringFormRoundTrips() {
        assertEquals(Optional.of(TestFixtures.NETHER_3),
                CommunityKey.tryParse(TestFixtures.NETHER_3.asString()));
    }

    @Test
    void malformedInputFailsSafely() {
        assertTrue(CommunityKey.tryParse(null).isEmpty());
        assertTrue(CommunityKey.tryParse("").isEmpty());
        assertTrue(CommunityKey.tryParse("3").isEmpty(), "a bare id is ambiguous and must be rejected");
        assertTrue(CommunityKey.tryParse("minecraft:overworld/").isEmpty());
        assertTrue(CommunityKey.tryParse("minecraft:overworld/x").isEmpty());
        assertTrue(CommunityKey.tryParse("NOT A DIMENSION/3").isEmpty());
        assertTrue(CommunityKey.tryParse("minecraft:overworld/-1").isEmpty());
    }

    @Test
    void negativeIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommunityKey(new ResourceLocation("minecraft:overworld"), -1));
        assertTrue(CommunityKey.of(new ResourceLocation("minecraft:overworld"), -1).isEmpty());
    }

    @Test
    void malformedNbtIsSkippedNotThrown() {
        assertTrue(CommunityKey.load(new CompoundTag()).isEmpty());
        CompoundTag badDimension = new CompoundTag();
        badDimension.putString("dim", "NOT A DIMENSION");
        badDimension.putInt("village", 1);
        assertTrue(CommunityKey.load(badDimension).isEmpty());

        CompoundTag negativeId = new CompoundTag();
        negativeId.putString("dim", "minecraft:overworld");
        negativeId.putInt("village", -5);
        assertTrue(CommunityKey.load(negativeId).isEmpty());
    }

    /**
     * The exact order does not matter; that it is total, antisymmetric, and stable does, because UI
     * lists and witness caps must not depend on hash iteration order.
     *
     * <p>Note that {@code ResourceLocation.compareTo} compares <em>path first</em>, then namespace, so
     * {@code minecraft:overworld} sorts before {@code minecraft:the_nether} — the reverse of what
     * reading the string left to right suggests.
     */
    @Test
    void orderingIsDeterministic() {
        assertTrue(TestFixtures.OVERWORLD_3.compareTo(TestFixtures.OVERWORLD_7) < 0,
                "within one dimension, lower village ids sort first");
        int across = TestFixtures.NETHER_3.compareTo(TestFixtures.OVERWORLD_3);
        assertTrue(across != 0, "different dimensions must never compare equal");
        assertEquals(-Integer.signum(across),
                Integer.signum(TestFixtures.OVERWORLD_3.compareTo(TestFixtures.NETHER_3)),
                "ordering must be antisymmetric");
        assertFalse(TestFixtures.OVERWORLD_3.compareTo(TestFixtures.OVERWORLD_3) != 0);
    }
}
