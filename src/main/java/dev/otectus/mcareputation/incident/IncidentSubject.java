package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Someone or something an incident is <em>about</em> (spec §14): the villager who was hurt, the giver
 * who was helped, the child who was found.
 *
 * <p>The cached {@link #name} is what makes history survive the world: §35.1 requires a missing or
 * dead subject to keep displaying sensibly, and re-resolving a UUID to a name years later is not
 * possible once the entity and its family-tree node are gone. The name is therefore captured at
 * creation and never refreshed — it is a record of who this was at the time.
 *
 * <p>{@link #role} is a free-form authored label ({@code victim}, {@code giver}, {@code sponsor},
 * {@code beneficiary}, …) used for gossip phrasing and template variables. It is bounded and
 * lowercased but deliberately not an enum, so a datapack can introduce a role without a code change.
 */
public record IncidentSubject(SubjectKind kind, Optional<UUID> uuid, String name, Optional<String> role) {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_ROLE_LENGTH = 32;

    public static final Codec<IncidentSubject> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StrictCodecs.strictOptional(SubjectKind.CODEC, "kind", SubjectKind.OTHER).forGetter(IncidentSubject::kind),
            StrictCodecs.strictOptional(UUIDUtil.STRING_CODEC, "uuid").forGetter(IncidentSubject::uuid),
            StrictCodecs.strictOptional(Codec.STRING, "name", "").forGetter(IncidentSubject::name),
            StrictCodecs.strictOptional(Codec.STRING, "role").forGetter(IncidentSubject::role)
    ).apply(instance, IncidentSubject::new));

    public IncidentSubject {
        kind = kind == null ? SubjectKind.OTHER : kind;
        uuid = uuid == null ? Optional.empty() : uuid;
        name = bound(name, MAX_NAME_LENGTH);
        role = role == null ? Optional.<String>empty()
                : role.map(r -> bound(r, MAX_ROLE_LENGTH).toLowerCase(Locale.ROOT)).filter(r -> !r.isEmpty());
    }

    private static String bound(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public static IncidentSubject villager(UUID uuid, String name, String role) {
        return new IncidentSubject(SubjectKind.VILLAGER, Optional.ofNullable(uuid), name,
                Optional.ofNullable(role));
    }

    public static IncidentSubject player(UUID uuid, String name, String role) {
        return new IncidentSubject(SubjectKind.PLAYER, Optional.ofNullable(uuid), name,
                Optional.ofNullable(role));
    }

    public static IncidentSubject community(String name) {
        return new IncidentSubject(SubjectKind.COMMUNITY, Optional.empty(), name, Optional.empty());
    }

    public boolean hasRole(String candidate) {
        return role.isPresent() && role.get().equalsIgnoreCase(candidate);
    }

    /** What a template's {@code {subject}} variable expands to; falls back to the UUID's short form. */
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return uuid.map(id -> id.toString().substring(0, 8)).orElse("?");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.jsonName());
        uuid.ifPresent(id -> tag.putUUID("uuid", id));
        if (!name.isEmpty()) {
            tag.putString("name", name);
        }
        role.ifPresent(r -> tag.putString("role", r));
        return tag;
    }

    /**
     * Lenient read (§13.6). A malformed subject entry yields an {@link SubjectKind#OTHER} placeholder
     * rather than an exception, so one bad subject never discards the incident it belongs to.
     */
    public static IncidentSubject load(CompoundTag tag) {
        if (tag == null) {
            return new IncidentSubject(SubjectKind.OTHER, Optional.empty(), "", Optional.empty());
        }
        SubjectKind kind = SubjectKind.byName(tag.getString("kind")).orElse(SubjectKind.OTHER);
        Optional<UUID> uuid = Optional.empty();
        if (tag.hasUUID("uuid")) {
            try {
                uuid = Optional.of(tag.getUUID("uuid"));
            } catch (RuntimeException ignored) {
                // malformed UUID -> keep the cached name only
            }
        }
        Optional<String> role = tag.contains("role") ? Optional.of(tag.getString("role")) : Optional.empty();
        return new IncidentSubject(kind, uuid, tag.getString("name"), role);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        // Explicit lambdas, not method references: 1.21.1 added static ByteBuf overloads of
        // writeUUID/readUUID alongside the instance ones, so the reference is now ambiguous.
        buf.writeOptional(uuid, (b, value) -> b.writeUUID(value));
        buf.writeUtf(name, MAX_NAME_LENGTH);
        buf.writeOptional(role, (b, r) -> b.writeUtf(r, MAX_ROLE_LENGTH));
    }

    public static IncidentSubject read(FriendlyByteBuf buf) {
        SubjectKind kind = buf.readEnum(SubjectKind.class);
        Optional<UUID> uuid = buf.readOptional(b -> b.readUUID());
        String name = buf.readUtf(MAX_NAME_LENGTH);
        Optional<String> role = buf.readOptional(b -> b.readUtf(MAX_ROLE_LENGTH));
        return new IncidentSubject(kind, uuid, name, role);
    }
}
