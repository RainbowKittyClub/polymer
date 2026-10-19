package eu.pb4.polymer.virtualentity.mixin;


import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.impl.HolderHolder;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayNetworkHandlerMixin implements HolderHolder {
    @Unique
    private final ArrayList<ElementHolder> polymerVE$holders = new ArrayList<>();
    // Each holder's slot in polymerVE$holders, so a remove swaps the last one in instead of shifting.
    @Unique
    private final Reference2IntOpenHashMap<ElementHolder> polymerVE$holderIndex = new Reference2IntOpenHashMap<>();
    // Watched holders without an attachment, from setAttachment(null) or watching while unset; the
    // tick checks these instead of every holder. A getAttachment() override alone is not seen.
    @Unique
    private final ReferenceOpenHashSet<ElementHolder> polymerVE$detached = new ReferenceOpenHashSet<>();
    @Shadow
    public ServerPlayer player;

    @Override
    public void polymer$addHolder(ElementHolder holderAttachment) {
        if (this.polymerVE$holderIndex.containsKey(holderAttachment)) {
            return;
        }
        this.polymerVE$holderIndex.put(holderAttachment, this.polymerVE$holders.size());
        this.polymerVE$holders.add(holderAttachment);
        if (holderAttachment.getAttachment() == null) {
            this.polymerVE$detached.add(holderAttachment);
        }
    }

    @Override
    public void polymer$removeHolder(ElementHolder holderAttachment) {
        if (!this.polymerVE$holderIndex.containsKey(holderAttachment)) {
            return;
        }
        var index = this.polymerVE$holderIndex.removeInt(holderAttachment);
        var last = this.polymerVE$holders.remove(this.polymerVE$holders.size() - 1);
        if (index < this.polymerVE$holders.size()) {
            this.polymerVE$holders.set(index, last);
            this.polymerVE$holderIndex.put(last, index);
        }
        this.polymerVE$detached.remove(holderAttachment);
    }

    @Override
    public Collection<ElementHolder> polymer$getHolders() {
        return this.polymerVE$holders;
    }

    @Override
    public void polymer$markDetached(ElementHolder holder) {
        if (this.polymerVE$holderIndex.containsKey(holder)) {
            this.polymerVE$detached.add(holder);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymerVE$tick(CallbackInfo ci) {
        if (this.polymerVE$detached.isEmpty()) {
            return;
        }
        var detached = this.polymerVE$detached.toArray(ELEMENT_HOLDERS);
        this.polymerVE$detached.clear();
        for (var holder : detached) {
            try {
                if (holder.getAttachment() == null) {
                    holder.stopWatching(this.player);
                }
            } catch (Throwable e) {
            }
            // Still watched and still unattached: try again next tick, as the old full walk did.
            if (holder.getAttachment() == null && this.polymerVE$holderIndex.containsKey(holder)) {
                this.polymerVE$detached.add(holder);
            }
        }
    }
    @ModifyVariable(method = "handlePickItemFromEntity", at = @At(value = "STORE", ordinal = 0))
    private Entity polymerVE$onPickEntity(Entity entity, ServerboundPickItemFromEntityPacket packet) {
        if (entity == null && !this.polymerVE$holders.isEmpty()) {
            for (var x : this.polymerVE$holders) {
                if (x.isPartOf(packet.id())) {
                    var i = x.getInteraction(packet.id(), this.player);
                    if (i != null) {
                        i.pickItem(this.player, packet.includeData());
                        break;
                    }
                }
            }
        }
        return entity;
    }


    @ModifyVariable(method = "handleInteract", at = @At(value = "STORE", ordinal = 0))
    private Entity polymerVE$onInteract(Entity entity, ServerboundInteractPacket packet) {
        if (entity == null && !this.polymerVE$holders.isEmpty()) {
            var id = packet.entityId();
            for (var x : this.polymerVE$holders) {
                if (x.isPartOf(id)) {
                    var i = x.getInteraction(id, this.player);
                    if (i != null) {
                        i.interact(this.player, packet.hand(), packet.location(), packet.usingSecondaryAction());
                        break;
                    }
                }
            }
        }
        return entity;
    }

    @ModifyVariable(method = "handleAttack", at = @At(value = "STORE", ordinal = 0))
    private Entity polymerVE$onAttack(Entity entity, ServerboundAttackPacket packet) {
        if (entity == null && !this.polymerVE$holders.isEmpty()) {
            var id = packet.entityId();
            for (var x : this.polymerVE$holders) {
                if (x.isPartOf(id)) {
                    var i = x.getInteraction(id, this.player);
                    if (i != null) {
                        i.attack(this.player);
                        break;
                    }
                }
            }
        }
        return entity;
    }
}
