package dev.otectus.mcareputation.reputation;

import com.mojang.serialization.Codec;
import dev.otectus.mcareputation.util.EnumCodecs;

import java.util.Optional;

/**
 * Where an earned title lives (spec §17.4). Deliberately identical in name and JSON spelling to MCA:
 * Quests' own {@code TitleScope}, so existing {@code mcaquests/titles/**.json} files load unchanged
 * through the compatibility path in §21.1.
 */
public enum TitleScope {

    /** Earned and displayed per community; keyed by the whole dimension-aware {@code CommunityKey}. */
    VILLAGE,

    /** Earned once and displayed everywhere. */
    GLOBAL;

    public static final Codec<TitleScope> CODEC = EnumCodecs.codec(TitleScope.class);

    public static Optional<TitleScope> byName(String raw) {
        return EnumCodecs.byName(TitleScope.class, raw);
    }

    public String jsonName() {
        return EnumCodecs.lower(this);
    }
}
