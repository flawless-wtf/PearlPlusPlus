package dev.zenith.pearlplusplus.logic;

import com.zenith.cache.data.entity.Entity;
import dev.zenith.pearlplusplus.types.OwnerInfo;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.zenith.Globals.CACHE;

public class PearlOwnerResolver {

    public Optional<OwnerInfo> resolve(Entity pearl, Map<Integer, Entity> entities) {
        return resolveFromProjectile(pearl, entities);
//        Optional<OwnerInfo> resolved = resolveFromProjectile(pearl, entities);
//        if (resolved.isPresent() || !PLUGIN_CONFIG.autoDetect.distanceCheck) {
//            return resolved;
//        }
//        return resolveFromClosestPlayer(pearl, entities);
    }

    private Optional<OwnerInfo> resolveFromProjectile(Entity pearl, Map<Integer, Entity> entities) {
        if (!(pearl.getObjectData() instanceof ProjectileData projectileData)) return Optional.empty();

        int ownerId = projectileData.getOwnerId();
        if (ownerId <= 0) return Optional.empty();

        Entity ownerEntity = entities.get(ownerId);
        UUID uuid = ownerEntity != null ? ownerEntity.getUuid() : null;
        String name = uuid != null ? resolveName(uuid).orElse(null) : null;

        return (uuid == null && name == null) ? Optional.empty() : Optional.of(new OwnerInfo(uuid, name));
    }

    private Optional<OwnerInfo> resolveFromClosestPlayer(Entity pearl, Map<Integer, Entity> entities) {
        UUID botUuid = CACHE.getProfileCache().getProfile().getId();
        Entity closest = entities.values().stream()
                .filter(e -> e.getEntityType() == EntityType.PLAYER)
                .filter(e -> !e.getUuid().equals(botUuid))
                .min(Comparator.comparingDouble(e -> distanceSq(e, pearl)))
                .orElse(null);

        if (closest == null) return Optional.empty();
        return Optional.of(new OwnerInfo(closest.getUuid(), resolveName(closest.getUuid()).orElse(null)));
    }

    private Optional<String> resolveName(UUID uuid) {
        return CACHE.getTabListCache().get(uuid)
                .map(PlayerListEntry::getName)
                .filter(name -> !name.isBlank());
    }

    private double distanceSq(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public OwnerInfo merge(OwnerInfo preferred, OwnerInfo secondary, OwnerInfo fallback) {
        return mergeTwo(preferred, mergeTwo(secondary, fallback));
    }

    private OwnerInfo mergeTwo(OwnerInfo a, OwnerInfo b) {
        if (a == null) return b;
        if (b == null) return a;
        UUID uuid = a.uuid() != null ? a.uuid() : b.uuid();
        String name = a.hasName() ? a.name() : b.name();
        return new OwnerInfo(uuid, name);
    }
}