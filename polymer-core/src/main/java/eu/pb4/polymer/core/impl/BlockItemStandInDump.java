package eu.pb4.polymer.core.impl;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.core.api.item.PolymerBlockItemStandIns;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Writes one JSON row per Polymer block item for a player's context: the client item Polymer
 * builds today, what {@link PolymerBlockItemStandIns} would give, how many stand-ins the block
 * shows and which test failed first.
 */
@ApiStatus.Internal
public final class BlockItemStandInDump {
    private BlockItemStandInDump() {
    }

    public static Path write(ServerPlayer player) throws IOException {
        var context = player.connection.getPacketContext();
        var lookup = player.level().registryAccess();
        var pack = PolymerBlockItemStandIns.hasMainPack(context);
        var rows = new JsonArray();
        var summary = new TreeMap<String, int[]>();
        var failures = new TreeMap<String, Integer>();
        int laterOnly = 0;

        var items = BuiltInRegistries.ITEM.stream()
                .filter(item -> item instanceof BlockItem && PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) instanceof PolymerItem)
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .toList();
        for (var item : items) {
            var blockItem = (BlockItem) item;
            var id = BuiltInRegistries.ITEM.getKey(item);
            var stack = item.getDefaultInstance();
            var today = PolymerItemUtils.getPolymerItemStack(stack, context, lookup);
            var survey = PolymerBlockItemStandIns.survey(blockItem, context);
            var decision = survey.decision();

            var row = new JsonObject();
            row.addProperty("item", id.toString());
            row.addProperty("block", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
            row.addProperty("clientItem", BuiltInRegistries.ITEM.getKey(today.getItem()).toString());
            row.add("clientPin", pin(today.get(DataComponents.BLOCK_STATE)));
            row.addProperty("decision", BuiltInRegistries.ITEM.getKey(decision.item()).toString());
            row.add("pin", pin(decision.pin()));
            row.addProperty("standIn", decision.standIn() != null ? BlockStateParser.serialize(decision.standIn()) : null);
            row.addProperty("standIns", decision.standIns());
            row.addProperty("allStandIns", survey.allStandIns());
            var standInBlocks = new JsonArray();
            survey.standInBlocks().stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).sorted().forEach(standInBlocks::add);
            row.add("standInBlocks", standInBlocks);
            row.addProperty("laterStatesOnly", survey.laterStatesOnly());
            row.addProperty("failed", decision.failure() != null ? decision.failure().name().toLowerCase(Locale.ROOT) : null);
            rows.add(row);

            var counts = summary.computeIfAbsent(id.getNamespace(), x -> new int[2]);
            counts[decision.pinned() ? 0 : 1]++;
            if (decision.failure() != null) {
                failures.merge(decision.failure().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            if (survey.laterStatesOnly()) {
                laterOnly++;
            }
        }

        var out = new JsonObject();
        out.addProperty("player", player.getGameProfile().name());
        out.addProperty("hasMainPack", pack);
        out.addProperty("pinUnstable", PolymerBlockItemStandIns.PIN_UNSTABLE);
        out.addProperty("blockItems", items.size());
        var byNamespace = new JsonObject();
        summary.forEach((namespace, counts) -> {
            var entry = new JsonObject();
            entry.addProperty("pinned", counts[0]);
            entry.addProperty("fireworkStar", counts[1]);
            byNamespace.add(namespace, entry);
        });
        out.add("byNamespace", byNamespace);
        var byFailure = new JsonObject();
        failures.forEach(byFailure::addProperty);
        out.add("byFailure", byFailure);
        out.addProperty("laterStatesOnly", laterOnly);
        out.add("rows", rows);

        var path = FabricLoader.getInstance().getGameDir().resolve("polymer")
                .resolve("block-items_" + player.getGameProfile().name() + (pack ? "_pack" : "_nopack") + ".json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(out), StandardCharsets.UTF_8);
        return path;
    }

    private static @Nullable JsonObject pin(@Nullable BlockItemStateProperties pin) {
        if (pin == null || pin.isEmpty()) {
            return null;
        }
        var out = new JsonObject();
        new TreeMap<>(pin.properties()).forEach(out::addProperty);
        return out;
    }
}
