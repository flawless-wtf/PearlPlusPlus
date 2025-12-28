package dev.zenith.pearlplusplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplusplus.PearlPlusConfig;
import dev.zenith.pearlplusplus.logic.PearlManager;

import java.util.List;
import java.util.UUID;

import static com.github.rfresh2.EventConsumer.of;
import static dev.zenith.pearlplusplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadModule extends Module {
    private final PearlManager pearlManager = new PearlManager(this);

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLoad.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(WhisperChatEvent.class, this::onWhisper));
    }

    private void onWhisper(WhisperChatEvent event) {
        if (!PLUGIN_CONFIG.autoLoad.enabled || event.outgoing()) return;

        String raw = event.message().trim();
        if (raw.isEmpty()) return;

        String[] parts = raw.split("\\s+");
        String command = parts[0].toLowerCase();

        var sender = event.sender();
        String name = sender.getName();
        UUID uuid = sender.getProfileId();

        var playerEntry = PLUGIN_CONFIG.players.get(uuid);
        if (playerEntry == null || playerEntry.pearls == null || playerEntry.pearls.isEmpty()) {
            info("Whisper from unauthorized user: " + name);
            return;
        }

        switch (command) {
            case "pearls" -> handleList(name, uuid);
            case "default" -> handleSetDefault(name, uuid, parts);
            case "rename" -> handleRename(name, uuid, playerEntry, parts);
            case "load" -> handleLoad(name, uuid, playerEntry, parts);
            default -> {
                // If the config allows weird noise prefixing, we might 
                // still want to check if it's a 'load' command mid-string.
                if (command.startsWith("load")) handleLoad(name, uuid, playerEntry, parts);
            }
        }
    }

    private void handleList(String name, UUID uuid) {
        whisper(name, pearlManager.pearlsList(uuid));
    }

    private void handleSetDefault(String name, UUID uuid, String[] parts) {
        if (parts.length < 2) {
            whisper(name, "Specify a pearl ID to set as default.");
            return;
        }
        String resolved = pearlManager.resolvePearlId(uuid, parts[1]);
        if (resolved == null) {
            whisper(name, "Pearl not found.");
            return;
        }
        pearlManager.setDefaultPearl(uuid, resolved);
        whisper(name, "Default pearl set to " + resolved + ".");
    }

    private void handleRename(String name, UUID uuid, PearlPlusConfig.PlayerPearls entry, String[] parts) {
        if (parts.length < 3) {
            whisper(name, "Usage: rename <oldId> <newId>");
            return;
        }
        String oldId = pearlManager.resolvePearlId(uuid, parts[1]);
        String newId = parts[2];

        if (oldId == null) {
            whisper(name, "Pearl not found.");
            return;
        }
        if (entry.pearls.keySet().stream().anyMatch(id -> id.equalsIgnoreCase(newId))) {
            whisper(name, "A pearl with that id already exists.");
            return;
        }

        if (pearlManager.renamePearl(uuid, oldId, newId)) {
            whisper(name, "Renamed " + oldId + " to " + newId + ".");
        } else {
            whisper(name, "Unable to rename pearl.");
        }
    }

    private void handleLoad(String name, UUID uuid, PearlPlusConfig.PlayerPearls entry, String[] parts) {
        int maxArgs = PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl ? 3 : 2;
        if (parts.length > maxArgs) {
            info("Too many arguments from " + name);
            return;
        }

        String targetId = null;

        if (parts.length == 1) {
            targetId = pearlManager.defaultPearlId(uuid);
        } else {
            String candidate = parts[1];
            String resolved = pearlManager.resolvePearlId(uuid, candidate);

            if (resolved != null) {
                targetId = resolved;
            } else if (PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
                targetId = pearlManager.defaultPearlId(uuid);
            }
        }

        if (targetId == null || !entry.pearls.containsKey(targetId)) {
            info("Load failed for " + name + " (Unknown ID)");
            whisper(name, "No authorized pearls found.");
            return;
        }

        var pearl = entry.pearls.get(targetId);

        discordAndIngameNotification(Embed.builder()
                .title("Received Whisper")
                .addField("Sender", name)
                .addField("Pearl", targetId)
        );

        whisper(name, "Loading pearl " + targetId + "...");
        if (!pearlManager.isPearlPresent(pearl)) {
            whisper(name, "No pearl detected. Attempting to load anyway.");
        }

        pearlManager.loadPearl(uuid, pearl, name);
    }

    private void whisper(String name, String message) {
        if (message != null && !message.isBlank()) {
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, message));
        }
    }
}