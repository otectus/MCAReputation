package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.api.CaptureResult;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.StandingAckResult;
import dev.otectus.mcareputation.api.StandingConsumer;
import dev.otectus.mcareputation.api.StandingDelivery;
import dev.otectus.mcareputation.api.StandingDeliveryBatch;
import dev.otectus.mcareputation.api.StandingEnvelope;
import dev.otectus.mcareputation.api.StandingRegistration;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent ordered source journal with durable consumer cursors.
 *
 * <p>Each save trims what every consumer has acknowledged, and since 0.6.1 it also keeps no more than a
 * retention bound ({@code [integration] standingJournalMaxEntries}) of the newest entries. Without the
 * bound a consumer that stalled, or whose mod was removed after it registered once, froze the trim point
 * for the life of the world, and every standing change ever made stayed in the save. A consumer that
 * falls further behind than the bound <b>lapses</b>: its cursor moves to the new start of the journal,
 * its next poll from the old position reports {@link StandingDeliveryBatch.Status#GAP}, and its
 * registration says where to resume. The entries between are gone, which is what GAP means.
 */
public final class StandingOutbox {
    /** The retention bound used when the configured one cannot be read. */
    public static final int DEFAULT_MAX_RETAINED = 4_096;

    private UUID epoch = UUID.randomUUID();
    private long nextSequence = 1L;
    private long trimmedThrough;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<ResourceLocation, ConsumerState> consumers = new LinkedHashMap<>();
    private final Map<ResourceLocation, StandingConsumer> callbacks = new LinkedHashMap<>();
    private Map<ResourceLocation, Long> observedThrough = new LinkedHashMap<>();
    private long durableTail;
    private long durableTrimmedThrough;
    private Map<ResourceLocation, ConsumerState> durableConsumers = Map.of();

    public boolean register(StandingConsumer consumer) {
        if (consumer == null || consumer.id() == null) return false;
        ResourceLocation id = consumer.id();
        callbacks.put(id, consumer);
        consumers.computeIfAbsent(id, ignored -> new ConsumerState(currentTail()));
        return true;
    }

    /** Detaches the consumer for this session; its durable cursor is kept, so it resumes on re-registration. */
    public void unregister(ResourceLocation id) {
        callbacks.remove(id);
    }

    /**
     * Drops a consumer outright, cursor and all: the operator's remedy for a consumer whose mod is gone
     * for good. Once saved, its entries no longer hold the trim point and its polls report
     * {@code UNREGISTERED}. Returns false when no such consumer exists.
     */
    public boolean forget(ResourceLocation id) {
        callbacks.remove(id);
        observedThrough.remove(id);
        return consumers.remove(id) != null;
    }

    public Optional<StandingRegistration> registration(ResourceLocation id) {
        ConsumerState state=consumers.get(id);
        return state==null?Optional.empty():Optional.of(new StandingRegistration(epoch,state.startAfter,state.ackThrough));
    }

    public StandingEnvelope append(UUID player, CommunityKey community, long communityRevision,
                                   int oldScore, int newScore, ChangeCause cause, boolean quiet,
                                   ResourceLocation source, UUID incidentId,
                                   ResourceLocation incidentType, long gameTime) {
        long sequence = nextSequence++;
        StandingEnvelope envelope = new StandingEnvelope(epoch, sequence,
                StandingEnvelope.eventId(epoch, sequence), player, community, communityRevision,
                oldScore, newScore, cause, quiet, source, Optional.ofNullable(incidentId),
                Optional.ofNullable(incidentType), gameTime);
        Map<ResourceLocation, CaptureResult> captures = new LinkedHashMap<>();
        callbacks.forEach((id, consumer) -> {
            CaptureResult result;
            try {
                result = consumer.capture(envelope);
                if (result == null) result = CaptureResult.failed(Map.of("reason", "null_capture"));
            } catch (Throwable throwable) {
                McaReputation.LOGGER.error("Standing outbox consumer {} failed during capture", id, throwable);
                result = CaptureResult.failed(Map.of("reason", throwable.getClass().getName()));
            }
            captures.put(id, result);
        });
        entries.add(new Entry(envelope, Map.copyOf(captures)));
        return envelope;
    }

    public StandingDeliveryBatch poll(ResourceLocation consumer, UUID requestedEpoch, long after, int limit) {
        if (consumer == null || requestedEpoch == null || limit < 1 || limit > 1_024) {
            return batch(StandingDeliveryBatch.Status.INVALID, List.of());
        }
        ConsumerState registered = durableConsumers.get(consumer);
        if (registered == null) return batch(StandingDeliveryBatch.Status.UNREGISTERED, List.of());
        if (!epoch.equals(requestedEpoch)) return batch(StandingDeliveryBatch.Status.EPOCH_MISMATCH, List.of());
        if (after < durableTrimmedThrough || after < registered.startAfter) {
            return batch(StandingDeliveryBatch.Status.GAP, List.of());
        }
        List<StandingDelivery> result = entries.stream()
                .filter(entry -> entry.envelope.sequence() > after && entry.envelope.sequence() <= durableTail)
                .sorted(Comparator.comparingLong(entry -> entry.envelope.sequence()))
                .limit(limit)
                .map(entry -> new StandingDelivery(entry.envelope,
                        entry.captures.getOrDefault(consumer,
                                CaptureResult.failed(Map.of("reason", "capture_missing")))))
                .toList();
        long observed = result.isEmpty() ? after : result.get(result.size() - 1).envelope().sequence();
        observedThrough.merge(consumer, observed, Math::max);
        return batch(StandingDeliveryBatch.Status.READY, result);
    }

    public StandingAckResult acknowledge(ResourceLocation consumer, UUID requestedEpoch, long through,
                                          boolean writable) {
        if (!writable) return StandingAckResult.READ_ONLY;
        if (consumer == null || requestedEpoch == null || through < 0) return StandingAckResult.INVALID;
        ConsumerState durable = durableConsumers.get(consumer);
        if (durable == null) return StandingAckResult.UNREGISTERED;
        if (!epoch.equals(requestedEpoch)) return StandingAckResult.EPOCH_MISMATCH;
        if (through > durableTail) return StandingAckResult.BEYOND_DURABLE_TAIL;
        if (through > observedThrough.getOrDefault(consumer, durable.ackThrough)) {
            return StandingAckResult.UNOBSERVED_PREFIX;
        }
        consumers.computeIfPresent(consumer, (ignored, state) ->
                new ConsumerState(state.startAfter, Math.max(state.ackThrough, through)));
        return StandingAckResult.ACCEPTED;
    }

    public UUID epoch() { return epoch; }
    public long durableTail() { return durableTail; }

    public boolean needsDurableSave() {
        return currentTail() != durableTail || !consumers.equals(durableConsumers);
    }

    /**
     * What a save writes: entries every consumer has acknowledged are trimmed, and so is everything older
     * than the newest {@code maxRetained}. A consumer whose acknowledged position falls below the trim
     * point is written as lapsed ({@link ConsumerState#lapsedAt}), starting after the new trim point.
     */
    public Snapshot snapshotForSave(int maxRetained) {
        long tail = currentTail();
        long minimumAck = consumers.values().stream().mapToLong(value -> value.ackThrough).min().orElse(tail);
        long retentionFloor = Math.max(0L, tail - Math.max(1, maxRetained));
        long trim = Math.max(trimmedThrough, Math.max(minimumAck, retentionFloor));
        Map<ResourceLocation, ConsumerState> cursors = new LinkedHashMap<>();
        consumers.forEach((id, state) -> cursors.put(id, state.ackThrough < trim ? ConsumerState.lapsedAt(trim) : state));
        List<Entry> retained = entries.stream().filter(entry -> entry.envelope.sequence() > trim).toList();
        return new Snapshot(epoch, nextSequence, trim, retained, Map.copyOf(cursors));
    }

    public void markDurable(Snapshot snapshot) {
        epoch = snapshot.epoch;nextSequence=snapshot.nextSequence;trimmedThrough=snapshot.trimmedThrough;
        entries.removeIf(entry -> entry.envelope.sequence() <= trimmedThrough);
        durableTail = nextSequence - 1;durableTrimmedThrough=trimmedThrough;
        durableConsumers=Map.copyOf(snapshot.consumers);
        // A cursor the save lapsed moves in memory too, once the lapse is on disk, so it is not lapsed
        // again on every save and a re-registration reports where the consumer resumes.
        snapshot.consumers.forEach((id, saved) -> consumers.computeIfPresent(id, (ignored, live) -> {
            if (live.ackThrough >= snapshot.trimmedThrough) return live;
            McaReputation.LOGGER.warn("[MCA: Reputation] Standing journal consumer {} fell behind the retention "
                    + "bound and lapsed: {} entr{} it never acknowledged were trimmed. Its next poll reports GAP; "
                    + "it resumes after entry {}. See [integration] standingJournalMaxEntries.", id,
                    snapshot.trimmedThrough - live.ackThrough,
                    snapshot.trimmedThrough - live.ackThrough == 1 ? "y" : "ies", snapshot.trimmedThrough);
            return saved;
        }));
    }

    /** One consumer as an operator sees it: its cursor, whether it registered this session, how far behind. */
    public record ConsumerView(ResourceLocation id, long startAfter, long acknowledgedThrough, boolean live,
                               long behind) {
    }

    /** Every consumer with a cursor, in registration order. Read-only. */
    public List<ConsumerView> consumerViews() {
        long tail = currentTail();
        List<ConsumerView> views = new ArrayList<>();
        consumers.forEach((id, state) -> views.add(new ConsumerView(id, state.startAfter, state.ackThrough,
                callbacks.containsKey(id), Math.max(0L, tail - state.ackThrough))));
        return views;
    }

    /** Entries currently kept in memory, whether or not they are saved yet. */
    public int retainedEntries() { return entries.size(); }
    public long trimmedThrough() { return trimmedThrough; }
    public long tail() { return currentTail(); }

    public CompoundTag save(Snapshot snapshot) {
        CompoundTag tag=new CompoundTag();tag.putUUID("Epoch",snapshot.epoch);tag.putLong("NextSequence",snapshot.nextSequence);tag.putLong("TrimmedThrough",snapshot.trimmedThrough);
        ListTag entryTags=new ListTag();snapshot.entries.forEach(entry->entryTags.add(entry.save()));tag.put("Entries",entryTags);
        ListTag consumerTags=new ListTag();snapshot.consumers.forEach((id,state)->consumerTags.add(state.save(id)));tag.put("Consumers",consumerTags);
        return tag;
    }

    public static StandingOutbox load(CompoundTag tag) {
        StandingOutbox outbox=new StandingOutbox();
        if(tag.hasUUID("Epoch"))outbox.epoch=tag.getUUID("Epoch");
        outbox.nextSequence=Math.max(1L,tag.getLong("NextSequence"));outbox.trimmedThrough=Math.max(0L,tag.getLong("TrimmedThrough"));
        for(Tag value:tag.getList("Entries",Tag.TAG_COMPOUND))try{outbox.entries.add(Entry.load((CompoundTag)value));}catch(RuntimeException exception){McaReputation.LOGGER.warn("Skipping malformed standing outbox entry",exception);}
        for(Tag value:tag.getList("Consumers",Tag.TAG_COMPOUND))try{CompoundTag consumer=(CompoundTag)value;ResourceLocation id=ResourceLocation.tryParse(consumer.getString("Id"));if(id!=null)outbox.consumers.put(id,ConsumerState.load(consumer));}catch(RuntimeException exception){McaReputation.LOGGER.warn("Skipping malformed standing outbox consumer",exception);}
        long loadedTail=Math.max(outbox.trimmedThrough,outbox.entries.stream().mapToLong(entry->entry.envelope.sequence()).max().orElse(0L));
        outbox.nextSequence=Math.max(outbox.nextSequence,loadedTail+1);outbox.durableTail=loadedTail;outbox.durableTrimmedThrough=outbox.trimmedThrough;outbox.durableConsumers=Map.copyOf(outbox.consumers);
        return outbox;
    }

    private long currentTail(){return nextSequence-1;}
    private StandingDeliveryBatch batch(StandingDeliveryBatch.Status status,List<StandingDelivery> deliveries){return new StandingDeliveryBatch(status,epoch,durableTail,durableTrimmedThrough,deliveries);}

    public record Snapshot(UUID epoch,long nextSequence,long trimmedThrough,List<Entry> entries,Map<ResourceLocation,ConsumerState> consumers) {}

    public record ConsumerState(long startAfter,long ackThrough) {
        ConsumerState(long startAfter){this(startAfter,startAfter);}
        /** A cursor that fell behind the retention bound: it resumes after the trim point it lost to. */
        static ConsumerState lapsedAt(long trimmedThrough){return new ConsumerState(trimmedThrough,trimmedThrough);}
        CompoundTag save(ResourceLocation id){CompoundTag tag=new CompoundTag();tag.putString("Id",id.toString());tag.putLong("StartAfter",startAfter);tag.putLong("AckThrough",ackThrough);return tag;}
        static ConsumerState load(CompoundTag tag){long start=Math.max(0L,tag.getLong("StartAfter"));return new ConsumerState(start,Math.max(start,tag.getLong("AckThrough")));}
    }

    public record Entry(StandingEnvelope envelope,Map<ResourceLocation,CaptureResult> captures) {
        CompoundTag save(){CompoundTag tag=new CompoundTag();tag.putUUID("Epoch",envelope.epoch());tag.putLong("Sequence",envelope.sequence());tag.putUUID("Event",envelope.eventId());tag.putUUID("Player",envelope.player());tag.put("Community",envelope.community().save());tag.putLong("CommunityRevision",envelope.communityRevision());tag.putInt("OldScore",envelope.oldScore());tag.putInt("NewScore",envelope.newScore());tag.putString("Cause",envelope.cause().name());tag.putBoolean("Quiet",envelope.quiet());tag.putString("Source",envelope.source().toString());envelope.incidentId().ifPresent(id->tag.putUUID("Incident",id));envelope.incidentType().ifPresent(id->tag.putString("IncidentType",id.toString()));tag.putLong("GameTime",envelope.gameTime());ListTag captureTags=new ListTag();captures.forEach((id,result)->{CompoundTag capture=new CompoundTag();capture.putString("Id",id.toString());capture.putString("Status",result.status().name());CompoundTag payload=new CompoundTag();result.payload().forEach(payload::putString);capture.put("Payload",payload);captureTags.add(capture);});tag.put("Captures",captureTags);return tag;}
        static Entry load(CompoundTag tag){CommunityKey community=CommunityKey.load(tag.getCompound("Community")).orElseThrow();ChangeCause cause;try{cause=ChangeCause.valueOf(tag.getString("Cause"));}catch(IllegalArgumentException ignored){cause=ChangeCause.DEED;}ResourceLocation source=ResourceLocation.tryParse(tag.getString("Source"));if(source==null)throw new IllegalArgumentException("invalid source");ResourceLocation incidentType=tag.contains("IncidentType")?ResourceLocation.tryParse(tag.getString("IncidentType")):null;StandingEnvelope envelope=new StandingEnvelope(tag.getUUID("Epoch"),tag.getLong("Sequence"),tag.getUUID("Event"),tag.getUUID("Player"),community,tag.getLong("CommunityRevision"),tag.getInt("OldScore"),tag.getInt("NewScore"),cause,tag.getBoolean("Quiet"),source,tag.hasUUID("Incident")?Optional.of(tag.getUUID("Incident")):Optional.empty(),Optional.ofNullable(incidentType),tag.getLong("GameTime"));Map<ResourceLocation,CaptureResult> captures=new LinkedHashMap<>();for(Tag value:tag.getList("Captures",Tag.TAG_COMPOUND)){CompoundTag capture=(CompoundTag)value;ResourceLocation id=ResourceLocation.tryParse(capture.getString("Id"));if(id==null)continue;CaptureResult.Status status;try{status=CaptureResult.Status.valueOf(capture.getString("Status"));}catch(IllegalArgumentException ignored){status=CaptureResult.Status.FAILED;}Map<String,String> payload=new LinkedHashMap<>();CompoundTag values=capture.getCompound("Payload");values.getAllKeys().forEach(key->payload.put(key,values.getString(key)));captures.put(id,new CaptureResult(status,payload));}return new Entry(envelope,Map.copyOf(captures));}
    }
}
