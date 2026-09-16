package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The subtrees a load could not use, kept instead of thrown away (spec §5 F09, §9).
 *
 * <p>"Skip the malformed entry and carry on" keeps a world openable, which is right, but on its own it
 * also means the only copy of a damaged record is the one being overwritten by the next autosave. This
 * holds those raw tags in memory with the reason and the path that produced them, and writes them once
 * per server start to a file beside the level, so an operator has something to inspect and a support
 * request has evidence attached.
 *
 * <p>Count-bounded: a systematically corrupt file must not turn a load problem into a memory problem.
 */
public final class SaveQuarantine {

    /** Raw subtrees held in memory across one server lifetime. */
    public static final int MAX_ENTRIES = 64;

    /** {@code <world>/mcareputation-quarantine.nbt}. */
    public static final String FILE_NAME = McaReputation.MOD_ID + "-quarantine.nbt";

    /** One rejected subtree: where it came from, why it was rejected, and the tag itself. */
    public record Entry(String path, String reason, Tag tag) {
    }

    private static final List<Entry> ENTRIES = Collections.synchronizedList(new ArrayList<>());
    private static int dropped;
    private static int profilePayloads;
    private static boolean written;

    private SaveQuarantine() {
    }

    /** Holds one rejected subtree. Silently counts, rather than growing, once the bound is reached. */
    public static void hold(String path, String reason, Tag tag) {
        if (tag == null) {
            return;
        }
        synchronized (ENTRIES) {
            if (ENTRIES.size() >= MAX_ENTRIES) {
                dropped++;
                return;
            }
            ENTRIES.add(new Entry(path == null ? "?" : path, reason == null ? "?" : reason, tag.copy()));
        }
    }

    /**
     * Holds a profile evidence payload the loader could not read, keeping the scalar incident that
     * carried it (§19.4).
     *
     * <p>Counted separately from the bound, and deliberately still counted once the bound is reached:
     * a quarantined contribution makes profile coverage incomplete, and "we stopped keeping copies"
     * must not read as "there was nothing wrong". §19.3's coverage rule depends on this number being
     * honest rather than on the held tags being complete.
     */
    public static void holdProfilePayload(String path, String reason, Tag tag) {
        profilePayloads++;
        hold(path, reason, tag);
    }

    /**
     * How many profile payloads have been quarantined this server lifetime. Never decreases, and is
     * not capped by {@link #MAX_ENTRIES}.
     */
    public static int profilePayloadCount() {
        return profilePayloads;
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    public static List<Entry> entries() {
        synchronized (ENTRIES) {
            return List.copyOf(ENTRIES);
        }
    }

    /** A short operator-facing summary; the long form is the file. */
    public static String report() {
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) {
                return profilePayloads > 0
                        ? profilePayloads + " profile payload(s) were rejected beyond the held bound"
                        : "nothing quarantined";
            }
            StringBuilder out = new StringBuilder();
            out.append(ENTRIES.size()).append(" quarantined subtree(s)");
            if (profilePayloads > 0) {
                out.append(", ").append(profilePayloads)
                        .append(" of them profile payload(s) whose incident was kept");
            }
            if (dropped > 0) {
                out.append(" (+").append(dropped).append(" beyond the bound of ").append(MAX_ENTRIES)
                        .append(")");
            }
            for (Entry entry : ENTRIES) {
                out.append("\n  ").append(entry.path()).append(": ").append(entry.reason());
            }
            return out.toString();
        }
    }

    /** Test and world-change seam: forget everything held so far. */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        dropped = 0;
        profilePayloads = 0;
        written = false;
    }

    /**
     * Writes the held tags beside the level, once per server start. Never throws at its caller: this
     * is a diagnostic, and failing to record a diagnostic must not fail a server start.
     *
     * @return true when a file was written
     */
    public static boolean writeOnce(MinecraftServer server) {
        if (server == null || written || ENTRIES.isEmpty()) {
            return false;
        }
        written = true;
        try {
            CompoundTag root = new CompoundTag();
            root.putString("mod", McaReputation.MOD_ID);
            root.putInt("dropped", dropped);
            ListTag list = new ListTag();
            for (Entry entry : entries()) {
                CompoundTag held = new CompoundTag();
                held.putString("path", entry.path());
                held.putString("reason", entry.reason());
                held.put("tag", entry.tag());
                list.add(held);
            }
            root.put("entries", list);
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
            NbtIo.writeCompressed(root, file);
            McaReputation.LOGGER.warn("[MCA: Reputation] wrote {} quarantined saved-data subtree(s) to {}. "
                    + "Nothing was lost silently; inspect the file before repairing the world.",
                    list.size(), file);
            return true;
        } catch (Throwable t) {
            McaReputation.LOGGER.warn("[MCA: Reputation] could not write the quarantine file; the held "
                    + "subtrees remain in memory for /mcareputation debug", t);
            return false;
        }
    }
}
