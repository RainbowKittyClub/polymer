package eu.pb4.polymer.core.api.block;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Optional interface for Polymer sign blocks (or their overlays), which replaces the client's sign editor with a dialog.
 * Useful when the client-side sign uses another wood, as its editor would show that wood.
 * Blocks can also be registered without implementing it, with {@link PolymerSignEditing#register(net.minecraft.world.level.block.Block)}.
 */
public interface PolymerSignBlock {
    /**
     * Called when player starts editing a sign, after vanilla checks passed
     *
     * @param player Player editing the sign
     * @param sign   Edited sign
     * @param front  True when the front side is edited
     * @return Dialog to show, or null to keep the client's own sign editor
     */
    @Nullable
    default SignEditDialog getSignEditDialog(ServerPlayer player, SignBlockEntity sign, boolean front) {
        return SignEditDialog.defaults(player, sign);
    }
}
