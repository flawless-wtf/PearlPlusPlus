package dev.zenith.pearlplusplus.types;


import dev.zenith.pearlplusplus.PearlPlusConfig;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import static dev.zenith.pearlplusplus.PearlPlusPlugin.PLUGIN_CONFIG;

public record StoredPearlEntry(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
    public OwnerInfo ownerInfo() {
        return new OwnerInfo(ownerUuid, ownerName);
    }

    public static Optional<StoredPearlEntry> findStoredPearlByColumn(int blockX, int blockZ) {
        return PLUGIN_CONFIG.players.entrySet().stream()
                .flatMap(entry -> entry.getValue().pearls.values().stream()
                        .filter(pearl -> pearl.x == blockX && pearl.z == blockZ)
                        .map(pearl -> new StoredPearlEntry(entry.getKey(), entry.getValue().playerName, pearl)))
                .max(Comparator.comparingInt(pearl -> pearl.pearl().y));
    }
}
