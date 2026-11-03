package eu.pb4.polymer.bulkclient;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 26.1.x copy of native/…/PacketObservationsMixin: getX/getZ, and types still on EntityType. */
@Mixin(ClientPacketListener.class)
public class PacketObservationsMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void polymerTest$chunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) throws Exception {
        if (packet.getX() == 200 && packet.getZ() == 200) {
            var event = new JsonObject(); event.addProperty("kind", "chunk-with-light");
            polymerTest$record(event);
        }
    }

    @Inject(method = "handleAddEntity", at = @At("RETURN"))
    private void polymerTest$entity(ClientboundAddEntityPacket packet, CallbackInfo ci) throws Exception {
        if (packet.getType() == EntityType.BLOCK_DISPLAY) {
            var event = new JsonObject(); event.addProperty("kind", "display-spawn");
            event.addProperty("entityId", packet.getId()); polymerTest$record(event);
        }
    }

    @Unique private static synchronized void polymerTest$record(JsonObject event) throws Exception {
        Files.writeString(Path.of(System.getProperty("polymer.bulk.packets")), event + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
