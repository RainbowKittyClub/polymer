package eu.pb4.polymer.core.api.block;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.core.impl.PolymerImplUtils;
import eu.pb4.polymer.core.impl.interfaces.PolymerCommonPacketListenerExtension;
import eu.pb4.polymer.core.impl.other.SignBoards;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Sign edit dialogs, shown in place of the client's sign editor for signs whose client-side block uses another wood.
 * Lookup order: provider registered here, then the block's {@link PolymerSyncedObject} implementing {@link PolymerSignBlock}.
 */
public final class PolymerSignEditing {
    /**
     * Id of the custom click action sent by sign edit dialogs
     */
    public static final Identifier CLICK_ID = PolymerImplUtils.id("sign_edit");
    private static final PolymerSignBlock DEFAULT = new PolymerSignBlock() {};
    private static final Map<Block, PolymerSignBlock> PROVIDERS = new IdentityHashMap<>();

    private PolymerSignEditing() {
    }

    /**
     * Registers a sign block to use the default dialog
     */
    public static void register(Block block) {
        register(block, DEFAULT);
    }

    /**
     * Registers a sign block to use dialogs created by the provider
     */
    public static void register(Block block, PolymerSignBlock provider) {
        if (!(block instanceof SignBlock)) {
            throw new IllegalArgumentException(block + " is not a SignBlock");
        }
        PROVIDERS.put(block, provider);
        SignBoards.register(block);
    }

    @Nullable
    public static PolymerSignBlock getProvider(Block block) {
        var provider = PROVIDERS.get(block);
        if (provider != null) {
            return provider;
        } else if (block instanceof PolymerSignBlock signBlock) {
            return signBlock;
        }
        return PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) instanceof PolymerSignBlock signBlock ? signBlock : null;
    }

    /**
     * @return True if player has a sign edit dialog open
     */
    public static boolean isEditing(ServerPlayer player) {
        return player.connection != null && ((PolymerCommonPacketListenerExtension) player.connection).polymer$getSignEditSession() != null;
    }
}
