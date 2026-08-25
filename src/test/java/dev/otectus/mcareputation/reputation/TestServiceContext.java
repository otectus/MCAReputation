package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A {@link ServiceContext} for the transaction tests: an in-memory store, no online players, and an
 * event "bus" that records every post in order. A test that needs to behave like a broken add-on
 * installs a {@link #listener} that throws — {@code post} still records the event first, exactly as
 * the real bus delivers to listeners registered before the broken one.
 */
final class TestServiceContext implements ServiceContext {

    final ReputationSavedData data = ReputationSavedData.createForTest();
    final List<Event> posted = new ArrayList<>();

    boolean serverThread = true;

    /** Invoked after each post is recorded; throw from here to simulate a broken listener. */
    @Nullable
    Consumer<Event> listener;

    @Override
    public boolean isServerThread() {
        return serverThread;
    }

    @Override
    public ReputationSavedData data() {
        return data;
    }

    @Override
    @Nullable
    public ServerPlayer onlinePlayer(UUID playerId) {
        return null;
    }

    @Override
    public void post(Event event) {
        posted.add(event);
        if (listener != null) {
            listener.accept(event);
        }
    }

    List<Class<?>> postedTypes() {
        List<Class<?>> types = new ArrayList<>(posted.size());
        for (Event event : posted) {
            types.add(event.getClass());
        }
        return types;
    }

    <T extends Event> List<T> posted(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Event event : posted) {
            if (type.isInstance(event)) {
                out.add(type.cast(event));
            }
        }
        return out;
    }
}
