package dev.otectus.mcareputation.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.DataResult;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * A JSON tree reader that <b>rejects a duplicate object key</b> instead of silently keeping the last
 * value.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Gson builds an object by {@code put}ting each member into a map, so
 *
 * <pre>{@code { "points": 8, "points": 80 }}</pre>
 *
 * parses cleanly and yields {@code 80}. Every codec downstream then validates a number the file does
 * not visibly contain. Spec §9.6 requires the new profile schema to reject that rather than quietly
 * take the last value: an authored profile is frozen onto accepted deeds, so a pack whose file says
 * one thing and whose behaviour says another is not recoverable after the fact.
 *
 * <p>Scoped to the schema §9.6 names. The pre-existing incident, tier and title directories keep
 * Gson's behaviour, because tightening them would reject packs that load today.
 */
public final class StrictJson {

    /** Structural ceiling on nesting depth; a pathological file must not exhaust the parse stack. */
    public static final int MAX_DEPTH = 32;

    private StrictJson() {
    }

    public static DataResult<JsonElement> parse(String json) {
        return parse(new StringReader(json));
    }

    /** Reads one JSON value, erroring on any duplicate key at any depth. Never throws. */
    public static DataResult<JsonElement> parse(Reader source) {
        try (JsonReader reader = new JsonReader(source)) {
            // Matches the loader's existing leniency for everything except duplicate keys.
            reader.setLenient(true);
            JsonElement value = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return DataResult.error(() -> "trailing content after the top-level JSON value");
            }
            return DataResult.success(value);
        } catch (DuplicateKeyException e) {
            return DataResult.error(e::getMessage);
        } catch (IOException | RuntimeException e) {
            return DataResult.error(() -> e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new DuplicateKeyException("JSON nests deeper than " + MAX_DEPTH + " levels");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new DuplicateKeyException("duplicate key '" + name + "' at "
                                + reader.getPath());
                    }
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            // The raw lexeme, exactly as Gson's own parser keeps it, so an int stays an int and a
            // codec expecting Codec.INT does not see a double.
            case NUMBER -> {
                return new JsonPrimitive(new LazilyParsedNumber(reader.nextString()));
            }
            case STRING -> {
                return new JsonPrimitive(reader.nextString());
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new DuplicateKeyException("unexpected JSON token " + token
                    + " at " + reader.getPath());
        }
    }

    /** Internal signal; never escapes {@link #parse}. */
    private static final class DuplicateKeyException extends IOException {

        DuplicateKeyException(String message) {
            super(message);
        }
    }
}
