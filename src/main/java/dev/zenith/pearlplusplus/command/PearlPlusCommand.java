package dev.zenith.pearlplusplus.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.feature.api.minetools.MinetoolsApi;
import com.zenith.feature.api.minetools.model.MinetoolsUuidResponse;
import dev.zenith.pearlplusplus.module.AutoLoadModule;
import dev.zenith.pearlplusplus.module.AutoDetectModule;
import dev.zenith.pearlplusplus.logic.PearlManager;

import java.util.Optional;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplusplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlPlusCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearlplus")
            .category(CommandCategory.MODULE)
            .description("Allow players to load pearls without whitelist through whispers.")
            .usageLines(
                "<on/off>",
                "list",
                "add <playerName> <pearlId> <x> <y> <z>",
                "del <playerName> <pearlId>",
                "defaultpearlid <word|none>",
                "returnpos <on/off>",
                "strict <on/off>",
                "autodetect <on/off>",
                "autodetect temp <on/off>",
                "distancecheck <on/off>",
                "autodefault <on/off>"
            )
            .aliases("pp")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        LiteralArgumentBuilder<CommandContext> builder = command("pearlplus")
                .requires(Command::validateAccountOwner);

        builder.then(argument("toggle", toggle()).executes(c -> {
            boolean enabled = getToggle(c, "toggle");
            PLUGIN_CONFIG.autoLoad.enabled = enabled;
            MODULE.get(AutoLoadModule.class).syncEnabledFromConfig();
            c.getSource().getEmbed()
                    .title("PearlPlus " + toggleStrCaps(enabled));
            return 0;
        }));

        builder.then(literal("list")
                .executes(c -> {
                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoordsAllPlayers();
                    c.getSource().getEmbed().title("All Pearls").description(pearls);
                    return 0;
                })
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    Optional<MinetoolsUuidResponse> result =
                            MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                    if (result.isEmpty()) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return 0;
                    }
                    UUID uuid = result.get().uuid();
                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                    String pearls = manager.pearlsListWithCoords(uuid);
                    c.getSource().getEmbed().title("Pearls for " + name).description(pearls);
                    return 0;
                })));

        builder.then(literal("add")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars())
                                .then(argument("x", integer())
                                        .then(argument("y", integer())
                                                .then(argument("z", integer()).executes(c -> {
                                                    String name = getString(c, "playerName");
                                                    String pearlId = getString(c, "pearlId");
                                                    Optional<MinetoolsUuidResponse> result =
                                                            MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                                                    if (result.isEmpty()) {
                                                        c.getSource().getEmbed().title("Invalid username: " + name);
                                                        return 0;
                                                    }

                                                    int x = getInteger(c, "x");
                                                    int y = getInteger(c, "y");
                                                    int z = getInteger(c, "z");

                                                    UUID uuid = result.get().uuid();
                                                    PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                                                    manager.recordPearl(uuid, name, pearlId, x, y, z);
                                                    c.getSource().getEmbed()
                                                            .title("Pearl stored for " + name)
                                                            .description(String.format("%s at %d %d %d", pearlId, x, y, z));
                                                    return 0;
                                                })))))));

        builder.then(literal("del")
                .then(argument("playerName", wordWithChars())
                        .then(argument("pearlId", wordWithChars()).executes(c -> {
                            String name = getString(c, "playerName");
                            String pearlId = getString(c, "pearlId");
                            Optional<MinetoolsUuidResponse> result =
                                    MinetoolsApi.INSTANCE.getProfileFromUsername(name);
                            if (result.isEmpty()) {
                                c.getSource().getEmbed().title("Invalid username: " + name);
                                return 0;
                            }

                            UUID uuid = result.get().uuid();
                            PearlManager manager = new PearlManager(MODULE.get(AutoDetectModule.class));
                            String resolvedPearlId = manager.resolvePearlId(uuid, pearlId);
                            if (resolvedPearlId == null) {
                                c.getSource().getEmbed().title("Pearl not found for " + name);
                                return 0;
                            }

                            manager.removePearl(uuid, resolvedPearlId);
                            c.getSource().getEmbed().title("Removed pearl " + resolvedPearlId + " for " + name);
                            return 0;
                        }))));

        builder.then(literal("defaultpearlid")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String word = getString(c, "word");
                    if ("none".equalsIgnoreCase(word)) {
                        PLUGIN_CONFIG.defaultPearlId = null;
                        c.getSource().getEmbed().title("Pearl ID word cleared; using player names");
                    } else {
                        PLUGIN_CONFIG.defaultPearlId = word;
                        c.getSource().getEmbed().title("Pearl ID word set to '" + word + "'");
                    }
                    return 0;
                })));

        builder.then(literal("strict")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean strict = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl = !strict;
                    c.getSource().getEmbed()
                            .title("PearlPlus strict " + toggleStrCaps(strict));
                    return 0;
                })));

        builder.then(literal("returnpos")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.returnToStartPos = enabled;
                    c.getSource().getEmbed()
                            .title("PearlPlus Return to Start " + toggleStrCaps(enabled));
                    return 0;
                })));

        builder.then(literal("autodetect")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoDetect.enabled = enabled;

                    AutoDetectModule module = MODULE.get(AutoDetectModule.class);
                    module.syncEnabledFromConfig();
                    if (enabled) {
                        module.markExistingPearls();
                    }

                    c.getSource().getEmbed()
                            .title("PearlPlus Autodetect " + toggleStrCaps(enabled));
                    return 0;
                }))
                .then(literal("temp")
                        .then(argument("toggle", toggle()).executes(c -> {
                            boolean enabled = getToggle(c, "toggle");
                            PLUGIN_CONFIG.autoDetect.temporaryMode = enabled;

//                            AutoDetectModule module = MODULE.get(AutoDetectModule.class);
//                            module.onTemporaryModeToggle(enabled);

                            c.getSource().getEmbed()
                                    .title("PearlPlus Autodetect Temp Mode " + toggleStrCaps(enabled));
                            return 0;
                        }))))
                .then(literal("area")
                        .then(argument("state", toggle()).executes(c -> {
                            boolean state = getToggle(c, "state");
                            PLUGIN_CONFIG.autoDetect.area.enabled = state;
                            c.getSource().getEmbed()
                                    .title("PearlPlus Area Filter " + toggleStrCaps(state))
                                    .description(state ? "Pearls will only register inside the defined box." : "Pearls will register anywhere.");
                            return 0;
                        }))

                        .then(argument("x1", integer())
                            .then(argument("y1", integer())
                                    .then(argument("z1", integer())
                                            .then(argument("x2", integer())
                                                    .then(argument("y2", integer())
                                                            .then(argument("z2", integer()).executes(c -> {
                                                                int x1 = getInteger(c, "x1");
                                                                int y1 = getInteger(c, "y1");
                                                                int z1 = getInteger(c, "z1");
                                                                int x2 = getInteger(c, "x2");
                                                                int y2 = getInteger(c, "y2");
                                                                int z2 = getInteger(c, "z2");

                                                                // Set the bounding box in config
                                                                PLUGIN_CONFIG.autoDetect.area.set(x1, y1, z1, x2, y2, z2);

                                                                c.getSource().getEmbed()
                                                                        .title("AutoDetect Area Updated")
                                                                        .description(String.format(
                                                                                "Pearls will only register if they land between:\n" +
                                                                                        "**Point A**: %d, %d, %d\n**Point B**: %d, %d, %d",
                                                                                x1, y1, z1, x2, y2, z2
                                                                        ));
                                                                return 0;
                                                            }))))))));

//        builder.then(literal("distancecheck")
//                .then(argument("toggle", toggle()).executes(c -> {
//                    boolean enabled = getToggle(c, "toggle");
//                    PLUGIN_CONFIG.autoDetect.distanceCheck = enabled;
//
//                    c.getSource().getEmbed()
//                            .title("PearlPlus Distance Check " + toggleStrCaps(enabled));
//                    return 0;
//                })));

        builder.then(literal("autodefault")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean enabled = getToggle(c, "toggle");
                    PLUGIN_CONFIG.autoLoad.autoDefaultToPresent = enabled;
                    c.getSource().getEmbed()
                            .title("PearlPlus Auto Default " + toggleStrCaps(enabled));
                    return 0;
                })));

        return builder;
    }
}
