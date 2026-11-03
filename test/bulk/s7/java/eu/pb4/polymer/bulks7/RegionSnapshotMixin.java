package eu.pb4.polymer.bulks7;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Client-side region observations for the S7 parity suite; independent of server fixture state. */
@Mixin(Minecraft.class)
public abstract class RegionSnapshotMixin {
    @Unique private String polymerS7$lastId = "";

    @Inject(method = "runTick", at = @At("TAIL"))
    private void polymerS7$frame(boolean advance, CallbackInfo ci) throws Exception {
        var client = (Minecraft) (Object) this;
        var requestFile = Path.of(System.getProperty("polymer.s7.request"));
        if (!Files.isRegularFile(requestFile)) return;
        var request = JsonParser.parseString(Files.readString(requestFile)).getAsJsonObject();
        String id = request.get("id").getAsString();
        if (id.equals(this.polymerS7$lastId)) return;
        this.polymerS7$lastId = id;
        var result = new JsonObject();
        result.addProperty("id", id);
        ClientLevel level = client.level;
        result.addProperty("inWorld", level != null && client.player != null);
        if (level != null && request.get("action").getAsString().equals("region")) {
            var boxes = new JsonObject();
            for (JsonElement element : request.getAsJsonArray("boxes")) {
                var box = element.getAsJsonObject();
                boxes.add(box.get("name").getAsString(), polymerS7$region(level, box));
            }
            result.add("boxes", boxes);
        }
        Files.writeString(Path.of(System.getProperty("polymer.s7.events")), result + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // Positions are relative to the box minimum so two regions edited different ways compare directly.
    @Unique
    private static JsonObject polymerS7$region(ClientLevel level, JsonObject box) {
        int x1 = box.get("x1").getAsInt(), y1 = box.get("y1").getAsInt(), z1 = box.get("z1").getAsInt();
        int x2 = box.get("x2").getAsInt(), y2 = box.get("y2").getAsInt(), z2 = box.get("z2").getAsInt();
        var out = new JsonObject();
        var cells = new JsonArray();
        boolean loaded = true;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    var pos = new BlockPos(x, y, z);
                    loaded &= level.hasChunkAt(pos);
                    var cell = new JsonObject();
                    cell.addProperty("at", (x - x1) + "," + (y - y1) + "," + (z - z1));
                    cell.addProperty("state", BlockStateParser.serialize(level.getBlockState(pos)));
                    cell.addProperty("light", level.getBrightness(LightLayer.BLOCK, pos));
                    cell.addProperty("lightAbove", level.getBrightness(LightLayer.BLOCK, pos.above()));
                    var blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        cell.addProperty("blockEntity", blockEntity.saveWithoutMetadata(level.registryAccess()).toString());
                    }
                    cells.add(cell);
                }
            }
        }
        out.addProperty("loaded", loaded);
        out.add("cells", cells);
        var displays = new JsonArray();
        // Displays have zero-size boxes and intersection is strict, so widen past the cell edges.
        var bounds = new AABB(x1 - 0.5, y1 - 0.5, z1 - 0.5, x2 + 1.5, y2 + 1.5, z2 + 1.5);
        for (var entity : level.getEntities((net.minecraft.world.entity.Entity) null, bounds, e -> e instanceof Display)) {
            var display = new JsonObject();
            display.addProperty("id", entity.getId());
            display.addProperty("at", polymerS7$rounded(entity.getX() - x1) + "," + polymerS7$rounded(entity.getY() - y1)
                    + "," + polymerS7$rounded(entity.getZ() - z1));
            display.addProperty("shows", entity instanceof Display.BlockDisplay blockDisplay
                    ? BlockStateParser.serialize(((BlockDisplayAccessor) blockDisplay).polymerS7$state())
                    : entity.getType().toString());
            displays.add(display);
        }
        out.add("displays", displays);
        return out;
    }

    @Unique
    private static String polymerS7$rounded(double value) {
        return Double.toString(Math.round(value * 64) / 64.0);
    }
}
