package eu.pb4.polymer.core.mixin.block;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {
    @Accessor("type")
    BlockSetType polymer$getType();
}
