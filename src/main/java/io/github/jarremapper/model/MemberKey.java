package io.github.jarremapper.model;

import java.util.Objects;

/**
 * Represents a single class member mapping (method or field).
 *
 * <p>For methods: name + descriptor (e.g. {@code (Ljava/lang/String;)V}).
 * For fields: name only; the descriptor here is the field's type descriptor (e.g. {@code I}).</p>
 *
 * <p>This record is immutable and serves as a key in {@code Map}s.</p>
 */
public record MemberKey(String name, String descriptor) {

    public MemberKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Member name must not be empty");
        }
    }

    public static MemberKey of(String name, String descriptor) {
        return new MemberKey(name, descriptor);
    }

    @Override
    public String toString() {
        return name + " " + descriptor;
    }
}
