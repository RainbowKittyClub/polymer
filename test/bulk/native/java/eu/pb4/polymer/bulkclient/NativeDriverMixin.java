package eu.pb4.polymer.bulkclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Adapted from the task-owned FAWE native driver; see test/bulk/NOTICE.txt. */
@Mixin(Minecraft.class)
public abstract class NativeDriverMixin {
    @Unique private String polymerTest$lastId = "";
    @Unique private String polymerTest$lastState = "";

    @Inject(method = "runTick", at = @At("TAIL"))
    private void polymerTest$frame(boolean advance, CallbackInfo ci) throws Exception {
        var client = (Minecraft) (Object) this;
        String state = client.gui.screen() instanceof DisconnectedScreen ? "disconnected"
                : client.level != null && client.player != null && client.gui.screen() == null ? "world" : "loading";
        if (!state.equals(this.polymerTest$lastState)) {
            this.polymerTest$lastState = state;
            var event = new JsonObject(); event.addProperty("state", state); polymerTest$event(event);
        }
        var requestFile = Path.of(System.getProperty("polymer.bulk.request"));
        if (!Files.isRegularFile(requestFile)) return;
        var request = JsonParser.parseString(Files.readString(requestFile)).getAsJsonObject();
        String id = request.get("id").getAsString();
        if (id.equals(this.polymerTest$lastId)) return;
        this.polymerTest$lastId = id;
        var result = new JsonObject(); result.addProperty("id", id);
        switch (request.get("action").getAsString()) {
            case "command" -> client.getConnection().sendCommand(request.get("command").getAsString());
            case "snapshot" -> {
                var pos = new BlockPos(3201, 80, 3200);
                result.addProperty("loaded", client.level.hasChunkAt(pos));
                result.addProperty("block", BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString());
                result.addProperty("blockLightAbove", client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos.above()));
                var ids = new JsonArray();
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof net.minecraft.world.entity.Display) ids.add(entity.getId());
                }
                result.add("displays", ids);
                int matching = 0;
                var expected = net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.getStateDefinition().getPossibleStates();
                for (int i = 0; i < 512; i++) {
                    var wirePos = new BlockPos(3200 + (i & 15), 84 + (i >> 8), 3200 + ((i >> 4) & 15));
                    if (client.level.getBlockState(wirePos) == expected.get(i)) matching++;
                }
                result.addProperty("globalPaletteMatches", matching);
            }
            case "stop" -> client.stop();
            default -> throw new IllegalArgumentException("Unknown native request");
        }
        polymerTest$event(result);
    }

    @Unique private static synchronized void polymerTest$event(JsonObject event) throws Exception {
        Files.writeString(Path.of(System.getProperty("polymer.bulk.events")), event + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
