package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.api.CaptureResult;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.StandingAckResult;
import dev.otectus.mcareputation.api.StandingConsumer;
import dev.otectus.mcareputation.api.StandingDeliveryBatch;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingOutboxTest {
    private static final ResourceLocation CONSUMER = new ResourceLocation("test", "factions");
    private static final ResourceLocation SOURCE = new ResourceLocation("test", "deed");
    private static final CommunityKey COMMUNITY =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 7);

    @Test
    void entriesAndRegistrationsBecomeVisibleOnlyAfterTheWholeStoreIsSaved(@TempDir Path directory) {
        SharedConstants.tryDetectVersion();
        ReputationSavedData data = ReputationSavedData.createForTest();
        StandingOutbox outbox = data.standingOutbox();
        UUID player = UUID.randomUUID();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of("kingdom", "test:riverbend"))));
        outbox.append(player, COMMUNITY, 4L, 10, 15, ChangeCause.DEED, false,
                SOURCE, null, null, 100L);
        data.setDirty();

        assertEquals(StandingDeliveryBatch.Status.UNREGISTERED,
                outbox.poll(CONSUMER, epoch, 0L, 8).status(),
                "an in-memory registration and entry must not be published before the save commits");

        data.save(directory.resolve("mcareputation.dat").toFile());

        StandingDeliveryBatch batch = outbox.poll(CONSUMER, epoch, 0L, 8);
        assertEquals(StandingDeliveryBatch.Status.READY, batch.status());
        assertEquals(1, batch.deliveries().size());
        assertEquals(player, batch.deliveries().get(0).envelope().player());
        assertEquals("test:riverbend", batch.deliveries().get(0).capture().payload().get("kingdom"));
    }

    @Test
    void failedSaveRetainsDirtyStateAndDoesNotAdvanceTheDurableTail(@TempDir Path directory)
            throws IOException {
        Path blockingParent = directory.resolve("not-a-directory");
        Files.writeString(blockingParent, "block");
        ReputationSavedData data = ReputationSavedData.createForTest();
        StandingOutbox outbox = data.standingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ignored(Map.of("reason", "policy"))));
        outbox.append(UUID.randomUUID(), COMMUNITY, 1L, 0, 1, ChangeCause.DEED, true,
                SOURCE, null, null, 1L);
        data.setDirty();

        data.save(blockingParent.resolve("mcareputation.dat").toFile());

        assertTrue(data.isDirty(), "a failed replacement must remain eligible for the next autosave");
        assertEquals(StandingDeliveryBatch.Status.UNREGISTERED,
                outbox.poll(CONSUMER, epoch, 0L, 8).status(),
                "failed IO must not publish the unsaved registration or entry");
    }

    @Test
    void acknowledgementCannotPassUnobservedOrUnsavedEntries() {
        StandingOutbox outbox = new StandingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        outbox.append(UUID.randomUUID(), COMMUNITY, 1L, 0, 1, ChangeCause.DEED, false,
                SOURCE, null, null, 1L);
        outbox.markDurable(outbox.snapshotForSave());

        assertEquals(StandingAckResult.UNOBSERVED_PREFIX,
                outbox.acknowledge(CONSUMER, epoch, 1L, true));
        assertEquals(1, outbox.poll(CONSUMER, epoch, 0L, 8).deliveries().size());
        assertEquals(StandingAckResult.BEYOND_DURABLE_TAIL,
                outbox.acknowledge(CONSUMER, epoch, 2L, true));
        assertEquals(StandingAckResult.ACCEPTED,
                outbox.acknowledge(CONSUMER, epoch, 1L, true));

        StandingOutbox.Snapshot acknowledged = outbox.snapshotForSave();
        assertEquals(1L, acknowledged.trimmedThrough());
        assertTrue(acknowledged.entries().isEmpty());
    }

    @Test
    void baselineExportIsByteStableAndDoesNotDirtyTheStore() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.getOrCreatePlayer(UUID.randomUUID()).getOrCreate(COMMUNITY).addBaseline(37, -1_000, 1_000);
        data.setDirty(false);
        net.minecraft.nbt.CompoundTag before = data.save(new net.minecraft.nbt.CompoundTag());

        var baselines = data.standingBaselines();

        assertEquals(1, baselines.size());
        assertEquals(37, baselines.get(0).score());
        assertEquals(before, data.save(new net.minecraft.nbt.CompoundTag()));
        assertTrue(!data.isDirty(), "preview export must not schedule a source write");
    }

    private static StandingConsumer consumer(CaptureResult result) {
        return new StandingConsumer() {
            @Override public ResourceLocation id() { return CONSUMER; }
            @Override public CaptureResult capture(dev.otectus.mcareputation.api.StandingEnvelope envelope) {
                return result;
            }
        };
    }
}
