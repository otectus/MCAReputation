package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import dev.otectus.mcareputation.util.EnumCodecs;

import java.util.Optional;

/** What sort of thing an {@link IncidentSubject} refers to (spec §14). */
public enum SubjectKind {

    /** An MCA villager, identified by entity UUID. */
    VILLAGER,

    /** Another player. */
    PLAYER,

    /** Any other entity (a pet, a mob in a situation objective). */
    ENTITY,

    /** The community itself, e.g. a project that benefited the whole village. */
    COMMUNITY,

    /** Anything that does not fit the above; carries only a cached display name. */
    OTHER;

    public static final Codec<SubjectKind> CODEC = EnumCodecs.codec(SubjectKind.class);

    public static Optional<SubjectKind> byName(String raw) {
        return EnumCodecs.byName(SubjectKind.class, raw);
    }

    public String jsonName() {
        return EnumCodecs.lower(this);
    }

    /** True when a UUID is meaningful for this kind (and so worth storing and resolving). */
    public boolean hasUuid() {
        return this == VILLAGER || this == PLAYER || this == ENTITY;
    }
}
