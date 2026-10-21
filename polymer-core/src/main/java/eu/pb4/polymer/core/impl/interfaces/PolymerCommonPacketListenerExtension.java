package eu.pb4.polymer.core.impl.interfaces;

import eu.pb4.polymer.core.impl.other.SignEditSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

@ApiStatus.Internal
@SuppressWarnings({"unused"})
public interface PolymerCommonPacketListenerExtension {
    void polymer$schedulePacket(Packet<?> packet, int duration);

    void polymer$delayAction(String identifier, int delay, Runnable action);

    @Nullable
    SignEditSession polymer$getSignEditSession();

    void polymer$setSignEditSession(@Nullable SignEditSession session);

    static PolymerCommonPacketListenerExtension of(ServerCommonPacketListenerImpl handler) {
        return (PolymerCommonPacketListenerExtension) handler;
    }
}
