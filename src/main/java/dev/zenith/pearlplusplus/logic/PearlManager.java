package dev.zenith.pearlplusplus.logic;

import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.mc.block.BlockPos;
import dev.zenith.pearlplusplus.PearlPlusConfig;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;
import java.util.stream.Collectors;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplusplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplusplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlManager {
    private final Module notifier;

    public PearlManager(Module notifier) {
        this.notifier = notifier;
    }

    public record PlayerPearl(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {}

    public Optional<PlayerPearl> findPearl(UUID ownerUuid, String pearlId) {
        return Optional.ofNullable(PLUGIN_CONFIG.players.get(ownerUuid))
                .flatMap(player -> Optional.ofNullable(player.pearls.get(pearlId))
                        .map(pearl -> new PlayerPearl(ownerUuid, player.playerName, pearl)));
    }

    public List<PearlPlusConfig.StoredPearl> listPearls(UUID ownerUuid) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        return (entry == null) ? List.of() : new ArrayList<>(entry.pearls.values());
    }

    public PearlPlusConfig.StoredPearl recordPearl(UUID ownerUuid, String ownerName, String pearlId, int x, int y, int z) {
        var entry = PLUGIN_CONFIG.players.computeIfAbsent(ownerUuid, k -> new PearlPlusConfig.PlayerPearls());
        entry.playerName = ownerName;

        if (entry.defaultPearlId == null || entry.defaultPearlId.isBlank()) {
            entry.defaultPearlId = pearlId;
        }

        return entry.pearls.compute(pearlId, (id, existing) -> {
            var p = (existing == null) ? new PearlPlusConfig.StoredPearl() : existing;
            p.pearlId = id;
            p.x = x; p.y = y; p.z = z;
            return p;
        });
    }


    public String pearlsListWithCoordsAllPlayers() {
        if (PLUGIN_CONFIG.players.isEmpty()) return "None";

        StringBuilder sb = new StringBuilder();
        boolean reportCoords = CONFIG.discord.reportCoords;
        Set<String> presentCoords = getPresentPearlCoords();

        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            PearlPlusConfig.PlayerPearls playerPearls = entry.getValue();
            if (playerPearls.pearls == null || playerPearls.pearls.isEmpty()) continue;

            String playerName = playerPearls.playerName != null ? playerPearls.playerName : entry.getKey().toString();
            sb.append("**").append(playerName).append("**\n");

            for (PearlPlusConfig.StoredPearl pearl : playerPearls.pearls.values()) {
                boolean isPresent = presentCoords.contains(pearl.x + "," + pearl.z);

                sb.append("- ").append(pearl.pearlId);
                if (isPresent) sb.append(" (Loaded)");

                sb.append(": ");
                if (reportCoords) {
                    sb.append("||[").append(pearl.x).append(", ").append(pearl.y).append(", ").append(pearl.z).append("]||");
                } else {
                    sb.append("coords hidden");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        String result = sb.toString().trim();
        return result.isBlank() ? "None" : result;
    }


    public void removePearl(UUID ownerUuid, String pearlId) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;

        entry.pearls.remove(pearlId);

        if (Objects.equals(pearlId, entry.defaultPearlId)) {
            entry.defaultPearlId = entry.pearls.keySet().stream().findFirst().orElse(null);
        }

        if (entry.pearls.isEmpty()) {
            PLUGIN_CONFIG.players.remove(ownerUuid);
        }
    }

    public boolean renamePearl(UUID ownerUuid, String oldId, String newId) {
        if (oldId == null || newId == null || oldId.isBlank() || newId.isBlank()) return false;

        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || !entry.pearls.containsKey(oldId) || entry.pearls.containsKey(newId)) return false;

        var pearl = entry.pearls.remove(oldId);
        pearl.pearlId = newId;
        entry.pearls.put(newId, pearl);

        if (oldId.equals(entry.defaultPearlId)) {
            entry.defaultPearlId = newId;
        }
        return true;
    }

    public String defaultPearlId(UUID ownerUuid) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return null;

        String def = entry.defaultPearlId;
        boolean autoDetect = PLUGIN_CONFIG.autoLoad.autoDefaultToPresent;

        // 1. Try configured default
        if (def != null && entry.pearls.containsKey(def)) {
            if (!autoDetect || isPearlPresent(entry.pearls.get(def))) return def;
        }

        // 2. Pick first available present pearl
        if (autoDetect) {
            for (var p : entry.pearls.values()) {
                if (isPearlPresent(p)) return p.pearlId;
            }
        }

        // 3. Fallback to existing default or just the first key
        return (def != null && entry.pearls.containsKey(def)) ? def : entry.pearls.keySet().iterator().next();
    }

    public void setDefaultPearl(UUID ownerUuid, String pearlId) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry != null && pearlId != null && entry.pearls.containsKey(pearlId)) {
            entry.defaultPearlId = pearlId;
        }
    }

    public String resolvePearlId(UUID ownerUuid, String pearlId) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || pearlId == null) return null;
        if (entry.pearls.containsKey(pearlId)) return pearlId; // Fast direct match

        return entry.pearls.keySet().stream()
                .filter(id -> id.equalsIgnoreCase(pearlId))
                .findFirst().orElse(null);
    }

    public boolean isPearlPresent(PearlPlusConfig.StoredPearl pearl) {
        if (pearl == null || CACHE.getEntityCache() == null) return false;
        if (!isWithinPresenceRange(pearl)) return false;

        return CACHE.getEntityCache().getEntities().values().stream()
                .anyMatch(e -> e.getEntityType() == EntityType.ENDER_PEARL
                        && (int)Math.floor(e.getX()) == pearl.x
                        && (int)Math.floor(e.getZ()) == pearl.z);
    }

    private boolean isWithinPresenceRange(PearlPlusConfig.StoredPearl pearl) {
        if (CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) return false;

        var p = CACHE.getPlayerCache().getThePlayer();
        double dx = p.getX() - pearl.x;
        double dy = p.getY() - pearl.y;
        double dz = p.getZ() - pearl.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double range = PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
        return distSq <= range * range;
    }

    public void loadPearl(UUID ownerUuid, PearlPlusConfig.StoredPearl pearl, String requesterName) {
        if (pearl == null) return;

        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue() || proxy.hasActivePlayer()) {
            String reason = (proxy == null || !proxy.isConnected()) ? "Bot is offline" : "Player is controlling";
            notifier.discordAndIngameNotification(Embed.builder().title("Can't Load Pearl").description(reason).errorColor());
            return;
        }

        var player = CACHE.getPlayerCache().getThePlayer();
        BlockPos startPos = player.blockPos();

        notifier.discordAndIngameNotification(Embed.builder().title("Loading Pearl").addField("Pearl", pearl.pearlId, false).primaryColor());

        BARITONE.rightClickBlock(pearl.x, pearl.y, pearl.z).addExecutedListener(f -> {
            var embed = Embed.builder().title("Pearl Loaded!").addField("Pearl ID", pearl.pearlId, false).successColor();
            if (requesterName != null) embed.addField("Requested By", requesterName, false);
            notifier.discordAndIngameNotification(embed);

            if (PLUGIN_CONFIG.autoLoad.returnToStartPos) {
                BARITONE.pathTo(startPos.x(), startPos.z()).addExecutedListener(f2 ->
                        notifier.discordAndIngameNotification(Embed.builder().description("Returned to start pos").successColor())
                );
            }
        });

//        removePearl(ownerUuid, pearl.pearlId);
    }

    public String pearlsList(UUID ownerUuid) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";

        // Performance Optimization: Cache current entity locations once rather than re-scanning
        // the entire entity list $O(N)$ for every single pearl in the list.
        Set<String> presentCoords = getPresentPearlCoords();

        return "PearlIDs: " + entry.pearls.values().stream()
                .map(p -> p.pearlId + (presentCoords.contains(p.x + "," + p.z) ? "" : "*"))
                .collect(Collectors.joining(", "));
    }

    public String pearlsListWithCoords(UUID ownerUuid) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";

        boolean showCoords = CONFIG.discord.reportCoords;
        return entry.pearls.values().stream()
                .map(p -> String.format("**%s**: %s", p.pearlId, showCoords ? String.format("||[%d, %d, %d]||", p.x, p.y, p.z) : "coords hidden"))
                .collect(Collectors.joining("\n"));
    }

    public String nextAvailablePearlId(UUID ownerUuid, String ownerName) {
        var entry = PLUGIN_CONFIG.players.get(ownerUuid);
        String base = Optional.ofNullable(PLUGIN_CONFIG.defaultPearlId)
                .filter(s -> !s.isBlank())
                .orElse(ownerName == null || ownerName.isBlank() ? "pearl" : ownerName)
                .replaceAll("\\s+", "");

        if (entry == null || !entry.pearls.containsKey(base)) return base;

        for (int i = 2; i < 1000; i++) {
            String candidate = base + i;
            if (!entry.pearls.containsKey(candidate)) return candidate;
        }
        return null;
    }

    private Set<String> getPresentPearlCoords() {
        if (CACHE.getEntityCache() == null) return Set.of();
        return CACHE.getEntityCache().getEntities().values().stream()
                .filter(e -> e.getEntityType() == EntityType.ENDER_PEARL)
                .map(e -> (int)Math.floor(e.getX()) + "," + (int)Math.floor(e.getZ()))
                .collect(Collectors.toSet());
    }

    public void info(String message) { LOG.info(message); }
}