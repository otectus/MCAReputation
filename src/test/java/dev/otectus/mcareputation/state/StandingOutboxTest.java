package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.api.CaptureResult;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.StandingAckResult;
import dev.otectus.mcareputation.api.StandingConsumer;
import dev.otectus.mcareputation.api.StandingDeliveryBatch;
import dev.otectus.mcareputation.api.StandingRegistration;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        outbox.markDurable(outbox.snapshotForSave(Integer.MAX_VALUE));

        assertEquals(StandingAckResult.UNOBSERVED_PREFIX,
                outbox.acknowledge(CONSUMER, epoch, 1L, true));
        assertEquals(1, outbox.poll(CONSUMER, epoch, 0L, 8).deliveries().size());
        assertEquals(StandingAckResult.BEYOND_DURABLE_TAIL,
                outbox.acknowledge(CONSUMER, epoch, 2L, true));
        assertEquals(StandingAckResult.ACCEPTED,
                outbox.acknowledge(CONSUMER, epoch, 1L, true));

        StandingOutbox.Snapshot acknowledged = outbox.snapshotForSave(Integer.MAX_VALUE);
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

    // ------------------------------------------------------------------ retention bound (0.6.1)

    private static final int BOUND = 4;

    private static void append(StandingOutbox outbox, int count) {
        for (int i = 0; i < count; i++) {
            outbox.append(UUID.randomUUID(), COMMUNITY, i, 0, 1, ChangeCause.DEED, false, SOURCE, null, null, i);
        }
    }

    @Test
    void aConsumerThatNeverAcknowledgesLapsesAndTheJournalStaysBounded() {
        StandingOutbox outbox = new StandingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 10);

        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(BOUND);
        assertEquals(BOUND, snapshot.entries().size(), "the save keeps the newest entries up to the bound");
        assertEquals(6L, snapshot.trimmedThrough());
        outbox.markDurable(snapshot);

        assertEquals(StandingDeliveryBatch.Status.GAP, outbox.poll(CONSUMER, epoch, 0L, 8).status(),
                "a poll from the lost position must say the entries are gone");
        StandingRegistration resume = outbox.registration(CONSUMER).orElseThrow();
        assertEquals(6L, resume.startAfter(), "the registration says where to resume");
        StandingDeliveryBatch batch = outbox.poll(CONSUMER, epoch, resume.startAfter(), 8);
        assertEquals(StandingDeliveryBatch.Status.READY, batch.status());
        assertEquals(BOUND, batch.deliveries().size());
        assertEquals(7L, batch.deliveries().get(0).envelope().sequence());
        assertEquals(StandingAckResult.ACCEPTED, outbox.acknowledge(CONSUMER, epoch, 10L, true),
                "a lapsed consumer that resumed acknowledges normally");
    }

    @Test
    void anUnregisteredConsumerLapsesTheSameWay() {
        StandingOutbox outbox = new StandingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        outbox.markDurable(outbox.snapshotForSave(BOUND));
        outbox.unregister(CONSUMER); // the consumer's mod was removed; its cursor is kept by design
        append(outbox, 10);

        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(BOUND);
        assertEquals(BOUND, snapshot.entries().size(),
                "a cursor nobody will ever advance must not hold every later change in the save");
        outbox.markDurable(snapshot);
        assertEquals(StandingDeliveryBatch.Status.GAP, outbox.poll(CONSUMER, epoch, 0L, 8).status());
        assertEquals(6L, outbox.registration(CONSUMER).orElseThrow().startAfter());
    }

    @Test
    void withNoConsumerEverythingIsTrimmedToTheTail() {
        StandingOutbox outbox = new StandingOutbox();
        append(outbox, 10);
        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(BOUND);
        assertEquals(10L, snapshot.trimmedThrough());
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void aConsumerWithinTheBoundKeepsItsCursor() {
        StandingOutbox outbox = new StandingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 10);
        outbox.markDurable(outbox.snapshotForSave(Integer.MAX_VALUE));
        assertEquals(8, outbox.poll(CONSUMER, epoch, 0L, 8).deliveries().size());
        assertEquals(StandingAckResult.ACCEPTED, outbox.acknowledge(CONSUMER, epoch, 8L, true));

        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(BOUND);
        assertEquals(8L, snapshot.trimmedThrough(), "the acknowledgement, not the bound, sets the trim here");
        outbox.markDurable(snapshot);
        StandingRegistration registration = outbox.registration(CONSUMER).orElseThrow();
        assertEquals(0L, registration.startAfter(), "a consumer that kept up is not lapsed");
        assertEquals(8L, registration.acknowledgedThrough());
        assertEquals(2, outbox.poll(CONSUMER, epoch, 8L, 8).deliveries().size());
    }

    @Test
    void aLapseIsAppliedOnceRatherThanOnEverySave() {
        StandingOutbox outbox = new StandingOutbox();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 10);
        outbox.markDurable(outbox.snapshotForSave(BOUND));

        assertFalse(outbox.needsDurableSave(), "the lapsed cursor in memory matches the one on disk");
        StandingOutbox.Snapshot again = outbox.snapshotForSave(BOUND);
        assertEquals(6L, again.trimmedThrough());
        assertEquals(6L, again.consumers().get(CONSUMER).startAfter());
    }

    @Test
    void aLapsedCursorSurvivesASaveAndReload() {
        StandingOutbox outbox = new StandingOutbox();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 10);
        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(BOUND);
        outbox.markDurable(snapshot);

        StandingOutbox reloaded = StandingOutbox.load(outbox.save(snapshot));
        assertEquals(StandingDeliveryBatch.Status.GAP,
                reloaded.poll(CONSUMER, reloaded.epoch(), 0L, 8).status());
        assertEquals(StandingDeliveryBatch.Status.READY,
                reloaded.poll(CONSUMER, reloaded.epoch(), 6L, 8).status());
        assertEquals(BOUND, reloaded.retainedEntries());
    }

    @Test
    void forgettingAConsumerReleasesTheJournalAndItsPollsStop() {
        StandingOutbox outbox = new StandingOutbox();
        UUID epoch = outbox.epoch();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 10);
        outbox.markDurable(outbox.snapshotForSave(Integer.MAX_VALUE));
        assertEquals(10, outbox.retainedEntries(), "an unacknowledging consumer holds everything without a bound");

        assertTrue(outbox.forget(CONSUMER));
        assertFalse(outbox.forget(CONSUMER), "forgetting twice finds nothing the second time");
        StandingOutbox.Snapshot snapshot = outbox.snapshotForSave(Integer.MAX_VALUE);
        assertEquals(10L, snapshot.trimmedThrough());
        assertTrue(snapshot.entries().isEmpty());
        outbox.markDurable(snapshot);
        assertEquals(StandingDeliveryBatch.Status.UNREGISTERED, outbox.poll(CONSUMER, epoch, 0L, 8).status());
        assertTrue(outbox.consumerViews().isEmpty());
    }

    @Test
    void theOperatorViewReportsLagAndWhetherTheConsumerIsLive() {
        StandingOutbox outbox = new StandingOutbox();
        outbox.register(consumer(CaptureResult.ready(Map.of())));
        append(outbox, 3);
        StandingOutbox.ConsumerView view = outbox.consumerViews().get(0);
        assertEquals(CONSUMER, view.id());
        assertTrue(view.live());
        assertEquals(3L, view.behind());
        outbox.unregister(CONSUMER);
        assertFalse(outbox.consumerViews().get(0).live());
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
