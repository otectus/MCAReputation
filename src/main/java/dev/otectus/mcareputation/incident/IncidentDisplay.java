package dev.otectus.mcareputation.incident;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.Optional;

/**
 * Fills an incident definition's display template with the facts of one particular deed (spec §15).
 *
 * <h2>The convention</h2>
 *
 * <p>A definition's {@code display} is a <em>template</em>, and a datapack does not write its
 * arguments — Reputation supplies them, in a fixed order, from the stored record:
 *
 * <ol>
 *   <li>{@code %1$s} — the primary subject's cached name ({@code {subject}})</li>
 *   <li>{@code %2$s} — the second subject's cached name ({@code {subject_2}})</li>
 *   <li>{@code %3$s} — the source title from context ({@code {source_title}}), e.g. a quest's name</li>
 *   <li>{@code %4$s} — the amount ({@code {amount}}), the incident's current contribution</li>
 * </ol>
 *
 * <p>Every slot is always supplied, so a lang string may use as few as it likes and adding a slot
 * later cannot break an existing translation. Missing facts become an empty string rather than a
 * literal {@code null} or a crash — a deed whose subject has been forgotten still reads as a sentence.
 *
 * <p>A definition whose display already carries its own arguments is left exactly as authored: a pack
 * that wants full control keeps it.
 */
public final class IncidentDisplay {

    /** Context key a caller sets to name the quest, project, or situation behind an incident. */
    public static final String CONTEXT_SOURCE_TITLE = "source_title";

    private IncidentDisplay() {
    }

    /**
     * The finished, player-facing line for one incident. Never throws: an unusable template degrades
     * to the raw definition text (§35.1).
     */
    public static Component resolve(IncidentDefinition definition, IncidentRecord record) {
        Component template = definition.display();
        try {
            if (!(template.getContents() instanceof TranslatableContents translatable)) {
                return template; // a literal display has nothing to fill
            }
            if (translatable.getArgs().length > 0) {
                return template; // the pack supplied its own arguments; respect them
            }
            List<IncidentSubject> subjects = record.subjects();
            String first = subjects.isEmpty() ? "" : subjects.get(0).displayName();
            String second = subjects.size() < 2 ? "" : subjects.get(1).displayName();
            String sourceTitle = record.context(CONTEXT_SOURCE_TITLE).orElse("");
            String amount = Integer.toString(record.currentContribution());

            MutableComponent resolved = Component.translatable(translatable.getKey(),
                    first, second, sourceTitle, amount);
            // Preserve any styling the pack put on the template.
            return resolved.setStyle(template.getStyle());
        } catch (Throwable t) {
            return template;
        }
    }

    /**
     * The arguments a gossip phrase should be rendered with, in the order the definition's
     * {@code gossip.with} names them (§30.4). Unknown names yield an empty component rather than
     * dropping a slot, so the argument positions a translation relies on stay stable.
     */
    public static List<Component> gossipArguments(IncidentDefinition definition, IncidentRecord record,
                                                  String playerName) {
        List<String> requested = definition.gossip().with();
        if (requested.isEmpty()) {
            // The sensible default pair: who did it, and to whom.
            return List.of(Component.literal(playerName == null ? "" : playerName),
                    Component.literal(record.subjects().isEmpty()
                            ? "" : record.subjects().get(0).displayName()));
        }
        return requested.stream().limit(GossipSpec.MAX_ARGUMENTS)
                .map(name -> Component.literal(gossipVariable(name, record, playerName)))
                .map(Component.class::cast)
                .toList();
    }

    private static String gossipVariable(String name, IncidentRecord record, String playerName) {
        List<IncidentSubject> subjects = record.subjects();
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "player" -> playerName == null ? "" : playerName;
            case "subject" -> subjects.isEmpty() ? "" : subjects.get(0).displayName();
            case "subject_2" -> subjects.size() < 2 ? "" : subjects.get(1).displayName();
            case "source_title" -> record.context(CONTEXT_SOURCE_TITLE).orElse("");
            case "giver" -> subjectNamed(record, "giver");
            case "amount" -> Integer.toString(record.currentContribution());
            default -> record.context(name).orElse("");
        };
    }

    private static String subjectNamed(IncidentRecord record, String role) {
        Optional<IncidentSubject> subject = record.subjectWithRole(role);
        return subject.map(IncidentSubject::displayName).orElse("");
    }
}
