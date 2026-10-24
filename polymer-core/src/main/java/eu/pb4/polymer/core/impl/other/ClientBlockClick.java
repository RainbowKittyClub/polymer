package eu.pb4.polymer.core.impl.other;

import eu.pb4.polymer.common.api.ScopedOverride;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerBlockItemStandIns;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.core.mixin.block.TrapDoorBlockAccessor;
import eu.pb4.polymer.core.impl.networking.BlockPacketUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * What a vanilla client did with a right-click on a block while holding a block item, worked out on
 * the server before its own interaction runs (MultiPlayerGameMode.performUseItemOn), and the scopes
 * that make the server's place sound and swing follow it. Only clicks where the item or the clicked
 * block is Polymer's are predicted; a vanilla item on a vanilla block keeps vanilla's answers.
 */
@ApiStatus.Internal
public final class ClientBlockClick {
    private static final ThreadLocal<ClientBlockClick> CURRENT = new ThreadLocal<>();
    // While set, the entity a sound excludes is final: no lift may null it.
    private static final ThreadLocal<Unit> FINAL_EXCLUSION = new ThreadLocal<>();
    // Where BlockItem.place put its block during the current click.
    private static final ThreadLocal<BlockPos> PLACED_AT = new ThreadLocal<>();

    private final Outcome outcome;
    private final ClientUse use;
    // The place sound the client's own placement played, or null when it played none.
    private final @Nullable SoundEvent ownPlaceSound;

    private ClientBlockClick(Outcome outcome, ClientUse use, @Nullable SoundEvent ownPlaceSound) {
        this.outcome = outcome;
        this.use = use;
        this.ownPlaceSound = ownPlaceSound;
    }

    /**
     * The client's own result for this click, or null when the click is not a block item's on
     * anything Polymer's.
     */
    public static @Nullable ClientBlockClick predict(ServerPlayer player, ItemStack stack, InteractionHand hand, BlockState clicked, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        var polymerBlock = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, clicked.getBlock()) instanceof PolymerBlock block ? block : null;
        var polymerItem = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, stack.getItem()) instanceof PolymerItem item ? item : null;
        if (polymerBlock == null && polymerItem == null) {
            return null;
        }
        var context = player.connection.getPacketContext();
        var clientState = polymerBlock != null
                ? polymerBlock.getPolymerBlockStateAt(clicked, hit.getBlockPos(), player)
                : PolymerBlockUtils.getPolymerBlockState(clicked, context);
        // The client item, built as Polymer sends it, so any pin counts, as does a rewrite of a vanilla stack.
        var clientStack = PolymerItemUtils.getPolymerItemStack(stack, context, player.registryAccess());
        boolean suppressed = player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
        var use = hand == InteractionHand.MAIN_HAND && !suppressed ? clientUse(player, clientState, hit, clientStack) : ClientUse.PASS;
        if (use != ClientUse.PASS) {
            return new ClientBlockClick(polymerBlock != null ? Outcome.CONSUMED_BY_STAND_IN : Outcome.CONSUMED, use, null);
        }
        if (!(clientStack.getItem() instanceof BlockItem clientItem)) {
            return new ClientBlockClick(Outcome.PASSED, use, null);
        }
        var predicted = clientItem.getBlock().defaultBlockState();
        var pin = clientStack.get(DataComponents.BLOCK_STATE);
        if (pin != null) {
            predicted = pin.apply(predicted);
        }
        var place = predicted.getSoundType().getPlaceSound();
        return new ClientBlockClick(Outcome.PLACED, use, PolymerBlockItemStandIns.isMuted(place, context) ? null : place);
    }

    /**
     * What the client's own useItemOn, then useWithoutItem, does with a main-hand click on this client-side
     * state while it holds the client item, for the vanilla blocks Polymer's stand-ins are drawn from.
     */
    public static ClientUse clientUse(ServerPlayer player, BlockState state, BlockHitResult hit, ItemStack clientStack) {
        var block = state.getBlock();
        if (block instanceof NoteBlock) {
            // useItemOn passes a head aimed at the top, so the client places it.
            return clientStack.is(ItemTags.NOTE_BLOCK_TOP_INSTRUMENTS) && hit.getDirection() == Direction.UP ? ClientUse.PASS : ClientUse.SWING;
        }
        if (block instanceof DispenserBlock || block instanceof CraftingTableBlock || block instanceof LeverBlock) {
            return ClientUse.SWING;
        }
        if (block instanceof FenceGateBlock) {
            return ClientUse.SWING_WITH_SOUND;
        }
        if (block instanceof ButtonBlock) {
            return state.getValue(ButtonBlock.POWERED) ? ClientUse.SILENT : ClientUse.SWING_WITH_SOUND;
        }
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand() ? ClientUse.SWING_WITH_SOUND : ClientUse.PASS;
        }
        if (block instanceof TrapDoorBlock trapdoor) {
            return ((TrapDoorBlockAccessor) trapdoor).polymer$getType().canOpenByHand() ? ClientUse.SWING_WITH_SOUND : ClientUse.PASS;
        }
        if (block instanceof SignBlock) {
            // useItemOn: CONSUME for an unwaxed sign; waxed only if its block entity data reached the client.
            return player.level().getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign && sign.isWaxed() ? ClientUse.SWING : ClientUse.SILENT;
        }
        if (block instanceof AbstractBedBlock || block instanceof LightBlock) {
            // SUCCESS_SERVER and CONSUME on the client.
            return ClientUse.SILENT;
        }
        if (block instanceof FlowerPotBlock pot) {
            return pot.getPotted() == Blocks.AIR ? ClientUse.SILENT : ClientUse.SWING;
        }
        if (block instanceof ShelfBlock shelf) {
            // useItemOn: CONSUME on the client for a held item on a slot.
            return shelf.getHitSlot(hit, state.getValue(ShelfBlock.FACING)).isPresent() ? ClientUse.SILENT : ClientUse.PASS;
        }
        if (block instanceof ChiseledBookShelfBlock shelf) {
            var slot = shelf.getHitSlot(hit, state.getValue(ChiseledBookShelfBlock.FACING));
            if (slot.isEmpty()) {
                return ClientUse.PASS;
            }
            return state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot.getAsInt())) ? ClientUse.SWING : ClientUse.SILENT;
        }
        if (block instanceof CampfireBlock) {
            return player.level().recipeAccess().propertySet(RecipePropertySet.CAMPFIRE_INPUT).test(clientStack) ? ClientUse.SILENT : ClientUse.PASS;
        }
        return ClientUse.PASS;
    }

    /** Makes this the current click until the override closes; a consumed vanilla click also fixes every exclusion. */
    public ScopedOverride enter() {
        CURRENT.set(this);
        PLACED_AT.remove();
        var exclusion = this.outcome == Outcome.CONSUMED ? finalExclusion() : ScopedOverride.NO_OP;
        return () -> {
            exclusion.close();
            CURRENT.remove();
        };
    }

    public static @Nullable ClientBlockClick current() {
        return CURRENT.get();
    }

    /** Fixes the excluded entity of every sound played until the override closes. */
    public static ScopedOverride finalExclusion() {
        if (FINAL_EXCLUSION.get() != null) {
            return ScopedOverride.NO_OP;
        }
        FINAL_EXCLUSION.set(Unit.INSTANCE);
        return FINAL_EXCLUSION::remove;
    }

    public static boolean isExclusionFinal() {
        return FINAL_EXCLUSION.get() != null;
    }

    /** Called when BlockItem.place reaches its sound, so the interaction knows it placed and where. */
    public static void markPlaced(BlockPos pos) {
        PLACED_AT.set(pos.immutable());
    }

    /** Where BlockItem.place put its block during the current click, or null; cleared by {@link #enter()}. */
    public static @Nullable BlockPos placedAt() {
        var pos = PLACED_AT.get();
        PLACED_AT.remove();
        return pos;
    }

    /**
     * Resends the column of note-block stand-ins above and below a block the client placed itself.
     * A client's own placement recomputes a note block's instrument from the block under it
     * (NoteBlock.updateShape), which turns an unstable stand-in into another model; the server's
     * state puts it right within a round trip.
     */
    public static void healColumn(ServerPlayer player, BlockPos placed) {
        var level = player.level();
        var context = player.connection.getPacketContext();
        for (var direction : new Direction[]{Direction.UP, Direction.DOWN}) {
            var pos = placed.relative(direction);
            while (PolymerBlockUtils.getPolymerBlockState(level.getBlockState(pos), context).is(Blocks.NOTE_BLOCK)) {
                BlockPacketUtil.sendUpdate(player, pos);
                pos = pos.relative(direction);
            }
        }
    }

    public Outcome outcome() {
        return this.outcome;
    }

    public ClientUse use() {
        return this.use;
    }

    /**
     * True when the client's own placement already played this sound, so the server's must leave it out. A client
     * whose placement sounded different (a stand-in that sounds unlike the real block) still gets the server's.
     */
    public boolean heardOwnSound(SoundEvent sound) {
        return this.ownPlaceSound != null && this.ownPlaceSound.location().equals(sound.location());
    }

    /** The client's own interaction passed, so a server placement has to show the swing. */
    public boolean passed() {
        return this.outcome == Outcome.PASSED;
    }

    /** The block the client sees took the click without a swing or a sound, so the server has to show both. */
    public boolean silentlyConsumed() {
        return this.use == ClientUse.SILENT;
    }

    public enum Outcome {
        /** The client placed its own block item. */
        PLACED,
        /** The client's view of the block consumed the click, but the server's block is another. */
        CONSUMED_BY_STAND_IN,
        /** The block consumed the click on the client exactly as it does on the server. */
        CONSUMED,
        /** The client did nothing: its item places no block. */
        PASSED
    }

    /** A vanilla client's own block use for a main-hand click (Minecraft.startUseItem swings only for a PREDICTED success). */
    public enum ClientUse {
        /** Its block use passed, so the item's own use follows. */
        PASS,
        /** SUCCESS: the client swings; any sound comes from the server. */
        SWING,
        /** SUCCESS, and the block's handler also plays its sound on the client (Level.playSound with that player). */
        SWING_WITH_SOUND,
        /** CONSUME or SUCCESS_SERVER: neither a swing nor a sound of its own. */
        SILENT
    }
}
