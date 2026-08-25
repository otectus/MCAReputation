package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable snapshot of one incident, for API consumers, packets, and UI (spec §25, §26).
 *
 * <p>Deliberately a copy rather than a view onto the live {@link IncidentRecord}: §25 requires queries
 * never to expose mutable collections, and handing another mod a reference to a record the
 * transaction service is about to mutate would make cross-mod behaviour depend on timing.
 *
 * <p>{@link #display} is resolved here, against whichever definition was loaded at the time. When the
 * definition has since been removed the text degrades to a fallback naming the id, while the numbers
 * — delta, contribution, status — come from the stored record and are unaffected (§35.1).
 */
public record ReputationIncidentView(
        UUID id,
        ResourceLocation type,
        CommunityKey community,
        Component display,
        long createdGameTime,
        long ageTicks,
        ResourceLocation source,
        int baseDelta,
        int currentContribution,
        IncidentVisibility visibility,
        IncidentSeverity severity,
        IncidentStatus status,
        List<IncidentSubject> subjects,
        Set<UUID> witnesses,
        Map<String, String> context,
        List<String> tags,
        boolean pinned) {

    public ReputationIncidentView {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        witnesses = witnesses == null ? Set.of() : Set.copyOf(witnesses);
        context = context == null ? Map.of() : Map.copyOf(context);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** Builds a view from a live record, resolving presentation against the current registry. */
    public static ReputationIncidentView of(IncidentRecord record, long gameTime) {
        IncidentDefinition definition = IncidentRegistry.getOrUnknown(record.type());
        return new ReputationIncidentView(
                record.id(),
                record.type(),
                record.community(),
                dev.otectus.mcareputation.incident.IncidentDisplay.resolve(definition, record),
                record.createdGameTime(),
                record.ageTicks(gameTime),
                record.source(),
                record.baseDelta(),
                record.currentContribution(),
                record.visibility(),
                record.severity(),
                record.status(),
                record.subjects(),
                record.witnesses(),
                record.context(),
                definition.tags(),
                record.pinned());
    }

    public boolean contributes() {
        return currentContribution != 0;
    }

    public boolean hasTag(String tag) {
        return tag != null && tags.contains(tag.toLowerCase(java.util.Locale.ROOT));
    }

    public Optional<IncidentSubject> subjectWithRole(String role) {
        return subjects.stream().filter(subject -> subject.hasRole(role)).findFirst();
    }

    /** The primary subject for template expansion — the first one, matching authoring order. */
    public Optional<IncidentSubject> primarySubject() {
        return subjects.isEmpty() ? Optional.empty() : Optional.of(subjects.get(0));
    }
}
