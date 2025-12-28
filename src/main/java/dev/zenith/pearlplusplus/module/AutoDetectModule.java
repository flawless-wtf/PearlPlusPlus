package dev.zenith.pearlplusplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplusplus.PearlPlusConfig;
import dev.zenith.pearlplusplus.logic.PearlManager;
import dev.zenith.pearlplusplus.logic.PearlOwnerResolver;
import dev.zenith.pearlplusplus.tracking.TrackedPearl;
import dev.zenith.pearlplusplus.types.*;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.zenith.Globals.*;
import static com.github.rfresh2.EventConsumer.of;
import static dev.zenith.pearlplusplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoDetectModule extends Module {
    private static final long GRACE_PERIOD_MS = 60_000L;

    private final Map<Integer, TrackedPearl> trackedPearls = new HashMap<>();
    private final PearlManager pearlManager = new PearlManager(this);
    private final PearlOwnerResolver ownerResolver = new PearlOwnerResolver();
    private long gracePeriodExpiry = 0L;
    private long suppressStoredPearlRemovalUntil = 0L;
    private boolean pendingReconnectGrace = false;

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
                of(ClientBotTick.class, event -> { if (PLUGIN_CONFIG.autoDetect.enabled) scan(); }),
                of(ClientBotTick.Stopped.class, event -> {
                    trackedPearls.clear();
                    pendingReconnectGrace = true;
                })
        );
    }

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoDetect.enabled;
    }

    @Override public void onEnable() {
        extendGrace();
        markExistingPearls();
    }

    private void scan() {
        if (pendingReconnectGrace) { extendGrace(); pendingReconnectGrace = false; }
        if (CACHE.getEntityCache() == null) return;

        Map<Integer, Entity> entities = CACHE.getEntityCache().getEntities();
        long now = System.currentTimeMillis();

        trackedPearls.entrySet().removeIf(entry -> {
            Entity e = entities.get(entry.getKey());
            if (e == null || e.getEntityType() != EntityType.ENDER_PEARL) {
                handlePearlMissing(entry.getValue());
                return true;
            }
            return false;
        });

        entities.values().stream()
                .filter(e -> e.getEntityType() == EntityType.ENDER_PEARL)
                .forEach(e -> updatePearl(e, entities, now));

        runAutoRegistration(now);
        if (PLUGIN_CONFIG.autoDetect.temporaryMode) runConsistencyCheck(entities);
    }

    private void updatePearl(Entity entity, Map<Integer, Entity> entities, long now) {
        BlockPosition pos = blockPosOf(entity);
        Optional<StoredPearlEntry> stored = findStored(pos);
        OwnerInfo resolved = ownerResolver.resolve(entity, entities).orElse(null);

        TrackedPearl tracked = trackedPearls.computeIfAbsent(entity.getEntityId(), id -> {
            OwnerInfo owner = ownerResolver.merge(resolved, stored.map(StoredPearlEntry::ownerInfo).orElse(null), null);
            var tp = new TrackedPearl(pos, owner, now);
            stored.ifPresent(s -> tp.setPearlId(s.pearl().pearlId));
            info(String.format("New pearl %d at %s thrown by %s", id, pos, tp.ownerSummary()));
            return tp;
        });

        tracked.updatePosition(pos, now);
        tracked.setOwner(ownerResolver.merge(resolved, stored.map(StoredPearlEntry::ownerInfo).orElse(null), tracked.owner()));
        stored.ifPresent(s -> tracked.setPearlId(s.pearl().pearlId));
    }

    private void runAutoRegistration(long now) {
        var area = PLUGIN_CONFIG.autoDetect.area;

        for (TrackedPearl tracked : trackedPearls.values()) {
            if (!tracked.isStable(now) || !tracked.ownerHasName()) continue;

            BlockPosition target = tracked.registrationPosition();

            // If the area is enabled and the pearl is outside, we skip registration
            if (!area.isInside(target.x(), target.y(), target.z())) {
                // Optional: We can clear the "registration notified" flag if they move out
                // so it can re-register if they move back in.
                continue;
            }

            Optional<StoredPearlEntry> existing = findStored(target);

            if (existing.isPresent()) {
                handleExistingPearl(tracked, existing.get());
            } else {
                registerNewPearl(tracked, target);
            }
        }
    }

    private void handleExistingPearl(TrackedPearl tracked, StoredPearlEntry stored) {
        if (isDifferentOwner(tracked.owner(), stored.ownerInfo())) {
            if (!tracked.conflictNotified()) {
                sendWhisper(tracked.owner().name(), "Pearl spot already belongs to " + stored.ownerInfo().name());
                tracked.markConflictNotified();
            }
            return;
        }

        tracked.setPearlId(stored.pearl().pearlId);
        pearlManager.recordPearl(tracked.owner().uuid(), tracked.owner().name(),
                stored.pearl().pearlId, tracked.blockX(), tracked.blockY(), tracked.blockZ());
    }

    private void registerNewPearl(TrackedPearl tracked, BlockPosition target) {
        String id = tracked.pearlId();
        if (id == null || id.isBlank()) {
            id = pearlManager.nextAvailablePearlId(tracked.owner().uuid(), tracked.owner().name());
        }

        if (id != null) {
            tracked.setPearlId(id);
            pearlManager.recordPearl(tracked.owner().uuid(), tracked.owner().name(), id, target.x(), target.y(), target.z());
            sendRegistrationNotifications(id, target, tracked);
            tracked.markRegistrationNotified();
        }
    }

    private void handlePearlMissing(TrackedPearl tracked) {
        info("Pearl at " + tracked.registrationPosition() + " broke/despawned");
        if (PLUGIN_CONFIG.autoDetect.temporaryMode && isProxyActive() && withinRemovalRange(tracked.registrationPosition())) {
            Optional<StoredPearlEntry> stored = findStored(tracked.registrationPosition());
            stored.ifPresent(s -> {
                sendRemovalNotifications(tracked);
                pearlManager.removePearl(s.ownerUuid(), s.pearl().pearlId);
            });
        }
    }

    private void sendRegistrationNotifications(String id, BlockPosition pos, TrackedPearl tracked) {
        String botName = CACHE.getProfileCache().getProfile().getName();
        String msg = String.format("Pearl Registered. Load me with /w %s load %s", botName, id);

        sendWhisper(tracked.owner().name(), msg);
        discordAndIngameNotification(Embed.builder()
                .title("Pearl Registered").addField("Pearl", id).addField("Owner", tracked.ownerSummary())
                .addField("Position", String.format("%d %d %d", pos.x(), pos.y(), pos.z())));
    }

    private void sendRemovalNotifications(TrackedPearl tracked) {
        String msg = tracked.pearlId() == null ? "A pearl was unregistered." : "Pearl " + tracked.pearlId() + " was unregistered.";
        sendWhisper(tracked.owner().name(), msg);

        discordAndIngameNotification(Embed.builder()
                .title("Pearl Removal Warning").addField("Owner", tracked.ownerSummary())
                .addField("Pearl", tracked.pearlId() == null ? "unknown" : tracked.pearlId())
                .addField("Position", String.format("%d %d %d", tracked.blockX(), tracked.blockY(), tracked.blockZ())));
    }

    private void sendUnregisterWhisper(OwnerInfo owner, String pearlId) {
        if (owner == null || !owner.hasName()) {
            return;
        }
        String target = owner.name();
        if (target == null || target.isBlank()) {
            return;
        }

        String message = (pearlId == null || pearlId.isBlank())
                ? "A pearl was unregistered."
                : "Pearl " + pearlId + " was unregistered.";

        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(target, message));
        info(String.format("Whispered pearl removal notice to %s for %s", owner.describe(), pearlId == null ? "unknown pearl" : pearlId));
    }

    private void sendRemovalWarning(TrackedPearl trackedPearl) {
        var builder = Embed.builder()
                .title("Pearl Removal Warning")
                .addField("Owner", trackedPearl.ownerSummary())
                .addField("Pearl", trackedPearl.pearlId() == null ? "unknown" : trackedPearl.pearlId())
                .addField("Position", String.format("%d %d %d", trackedPearl.blockX(), trackedPearl.blockY(), trackedPearl.blockZ()));
        discordAndIngameNotification(builder);
    }

    private void sendWhisper(String name, String message) {
        if (name != null) sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, message));
    }

    private void extendGrace() { gracePeriodExpiry = System.currentTimeMillis() + GRACE_PERIOD_MS; }
    private boolean isProxyActive() { return Proxy.getInstance() != null && Proxy.getInstance().isConnected() && !Proxy.getInstance().isInQueue(); }
    private BlockPosition blockPosOf(Entity e) { return new BlockPosition((int)Math.floor(e.getX()), (int)Math.floor(e.getY()), (int)Math.floor(e.getZ())); }

    private Optional<StoredPearlEntry> findStored(BlockPosition pos) {
        return PLUGIN_CONFIG.players.entrySet().stream()
                .flatMap(e -> e.getValue().pearls.values().stream()
                        .filter(p -> p.x == pos.x() && p.z == pos.z())
                        .map(p -> new StoredPearlEntry(e.getKey(), e.getValue().playerName, p)))
                .max(Comparator.comparingInt(p -> p.pearl().y));
    }

    private boolean withinRemovalRange(BlockPosition pos) {
        if (CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) return false;
        Entity p = CACHE.getPlayerCache().getThePlayer();
        return Math.sqrt(Math.pow(p.getX()-pos.x(),2) + Math.pow(p.getY()-pos.y(),2) + Math.pow(p.getZ()-pos.z(),2)) <= PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
    }

    private boolean isDifferentOwner(OwnerInfo a, OwnerInfo b) {
        if (a == null || b == null) return false;
        if (a.uuid() != null && b.uuid() != null) return !a.uuid().equals(b.uuid());
        return a.hasName() && b.hasName() && !a.name().equalsIgnoreCase(b.name());
    }

    private void runConsistencyCheck(Map<Integer, Entity> entities) {
//        if (!isTemporaryModeEnabled()) {
//            return;
//        }
        if (PLUGIN_CONFIG.players.isEmpty()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
//        if (System.currentTimeMillis() < suppressStoredPearlRemovalUntil) {
//            return;
//        }

        Set<String> activeIds = new HashSet<>();
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (tracked.pearlId() != null) {
                activeIds.add(tracked.pearlId());
            }
        }

        for (var entry : new HashMap<>(PLUGIN_CONFIG.players).entrySet()) {
            UUID ownerUuid = entry.getKey();
            var playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null) {
                continue;
            }
            for (var pearlEntry : new HashMap<>(playerPearls.pearls).entrySet()) {
                PearlPlusConfig.StoredPearl stored = pearlEntry.getValue();
                if (stored == null) {
                    continue;
                }
                if (activeIds.contains(stored.pearlId)) {
                    continue;
                }

                boolean pearlPresent = false;
                for (Entity entity : entities.values()) {
                    if (entity.getEntityType() == EntityType.ENDER_PEARL) {
                        BlockPosition position = BlockPosition.blockPositionOf(entity);
                        if (position.x() == stored.x && position.z() == stored.z) {
                            pearlPresent = true;
                            break;
                        }
                    }
                }
                if (pearlPresent) {
                    continue;
                }

                if (!withinRemovalRange(new BlockPosition(stored.x, stored.y, stored.z))) {
                    continue;
                }

                var trackedPearl = new TrackedPearl(new BlockPosition(stored.x, stored.y, stored.z), new OwnerInfo(ownerUuid, playerPearls.playerName), System.currentTimeMillis(), stored.pearlId);
                sendRemovalWarning(trackedPearl);
                sendUnregisterWhisper(trackedPearl.owner(), trackedPearl.pearlId());
                pearlManager.removePearl(ownerUuid, stored.pearlId);
            }
        }
    }

    public void markExistingPearls() {
        var cache = CACHE.getEntityCache();
        trackedPearls.clear();
        if (cache == null) {
            return;
        }

        Map<Integer, Entity> entities = cache.getEntities();
        long now = System.currentTimeMillis();

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.ENDER_PEARL) {
                continue;
            }

            BlockPosition position = BlockPosition.blockPositionOf(entity);
            StoredPearlEntry storedEntry = StoredPearlEntry.findStoredPearlByColumn(position.x(), position.z()).orElse(null);
            OwnerInfo storedOwner = storedEntry != null ? storedEntry.ownerInfo() : null;
            OwnerInfo resolvedOwner = ownerResolver.resolve(entity, entities).orElse(null);
            OwnerInfo owner = ownerResolver.merge(resolvedOwner, storedOwner, null);

            TrackedPearl tracked = new TrackedPearl(position, owner, now);
            tracked.setPearlId(storedEntry != null ? storedEntry.pearl().pearlId : null);
            trackedPearls.put(entity.getEntityId(), tracked);
        }
    }
}