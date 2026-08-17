package dev.otectus.mcareputation.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;

/**
 * The community argument: {@code <dimension>/<villageId>} — for example
 * {@code minecraft:overworld/3} — or the literal {@code here} (spec §24).
 *
 * <p>This is a real {@link ArgumentType} rather than {@code StringArgumentType} for two reasons that
 * are both parse-time properties:
 *
 * <ul>
 *   <li>An unquoted Brigadier string stops at {@code ':'} and {@code '/'}, so a community key could
 *       never be typed or tab-completed without quotes.</li>
 *   <li>It <b>rejects anything that is not a community</b> while parsing. Where a player argument and
 *       a community argument share a depth ({@code /mcareputation get <player|community>}), that is
 *       what disambiguates them: {@code Steve} fails here and falls through to the player branch,
 *       {@code minecraft:overworld/3} fails the player parser and lands here — in either order.</li>
 * </ul>
 *
 * <p>The parsed value is the raw token; resolution of {@code here} against the executor's position
 * stays server-side in the command (§27.2).
 */
public final class CommunityArgument implements ArgumentType<String> {

    private static final Collection<String> EXAMPLES = List.of("here", "minecraft:overworld/3");

    private static final DynamicCommandExceptionType BAD_COMMUNITY = new DynamicCommandExceptionType(
            raw -> Component.translatable("mcareputation.command.error.bad_community"));

    private static final String HERE = "here";

    private CommunityArgument() {
    }

    public static CommunityArgument community() {
        return new CommunityArgument();
    }

    public static String getCommunity(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        String raw = reader.getString().substring(start, reader.getCursor());
        if (HERE.equalsIgnoreCase(raw) || CommunityKey.tryParse(raw).isPresent()) {
            return raw;
        }
        reader.setCursor(start);
        throw BAD_COMMUNITY.createWithContext(reader, raw);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
