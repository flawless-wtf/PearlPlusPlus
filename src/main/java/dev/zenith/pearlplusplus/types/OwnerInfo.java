package dev.zenith.pearlplusplus.types;

import java.util.UUID;

public record OwnerInfo(UUID uuid, String name) {
    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    public String describe() {
        if (hasName()) {
            return uuid != null ? name + " (" + uuid + ")" : name;
        }
        return uuid != null ? uuid.toString() : "unknown";
    }
}