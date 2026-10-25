package eu.pb4.polymer.core.api.item;

import com.google.common.collect.MapMaker;
import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Chooses a Polymer block item's client item from its block's client-side stand-ins. A block that
 * shows one stand-in in every state, which a vanilla client can place itself and whose place sound
 * that client either mutes or gets right, gives that stand-in's own block item with a
 * {@code block_state} component pinning every property, so the client predicts the exact state.
 * Any other block gives a non-placing firework star, so the client predicts nothing. Only the states
 * a placement can make count: a block whose stand-ins differ in a property the world sets later,
 * such as lit or powered ({@link #LATER_PROPERTIES}), still pins.
 * <p>
 * Nothing in Polymer calls this by itself: a block item opts in by answering {@link #get} from
 * {@link PolymerItem#getPolymerItem(ItemStack, PacketContext)} and calling {@link #pin} from
 * {@link PolymerItem#modifyBasePolymerItemStack}. Stand-ins are read through the context's
 * {@link BlockMapper} and may depend on its resource pack status, so decisions are memoised per
 * block, mapper and pack status, and the first encode decides for everyone sharing those.
 */
public final class PolymerBlockItemStandIns {
    /**
     * Properties the world sets after a block is placed, such as redstone, light or use. States
     * where any of them differs from the default are left out of the decision; {@link #survey}
     * still counts them.
     */
    public static final Set<String> LATER_PROPERTIES = Set.of(
            "lit", "powered", "open", "triggered", "enabled", "extended", "occupied", "has_record",
            "has_book", "attached", "disarmed", "inverted", "unstable", "cracked", "bloom", "shrieking",
            "can_summon", "crafting", "ominous", "eye", "drag", "berries", "dusted", "age", "power",
            "level", "honey_level", "moisture", "note", "stage", "charges", "hatch", "bites", "delay",
            "tilt", "natural"
    );

    /**
     * Whether a block whose one stand-in is a note block outside a stable slot pins. Off by default,
     * so such a block gets the firework star. System property {@code polymer.standIns.pinUnstable}.
     */
    public static final boolean PIN_UNSTABLE = Boolean.parseBoolean(System.getProperty("polymer.standIns.pinUnstable", "false"));

    // Weak in the mapper, so a mapper built per player or per reset does not outlive its connection here.
    private static final Map<BlockMapper, Map<Key, Decision>> CACHE = new MapMaker().weakKeys().makeMap();
    private static final Map<Class<?>, Boolean> SURVIVAL_RULES = new ConcurrentHashMap<>();
    private static final List<Predicate<BlockState>> INVISIBLE = new CopyOnWriteArrayList<>();
    private static final List<BiPredicate<SoundEvent, PacketContext>> MUTED = new CopyOnWriteArrayList<>();
    private static final List<Predicate<BlockState>> UNSTABLE = new CopyOnWriteArrayList<>();

    private PolymerBlockItemStandIns() {
    }

    /**
     * The client item for this block item in this context, memoised.
     *
     * @param item    server-side block item
     * @param context context it is sent in, or null for none
     */
    public static Decision get(BlockItem item, @Nullable PacketContext context) {
        var byMapper = CACHE.computeIfAbsent(BlockMapper.getFrom(context), mapper -> new ConcurrentHashMap<>());
        var key = new Key(item.getBlock(), hasMainPack(context));
        var cached = byMapper.get(key);
        if (cached != null) {
            return cached;
        }
        var decision = decide(item, context, 2).decision();
        byMapper.put(key, decision);
        return decision;
    }

    /**
     * Sets {@link DataComponents#BLOCK_STATE} on the client stack when {@link #get} pinned a
     * stand-in; leaves it alone otherwise.
     *
     * @param out     client-side stack being built
     * @param item    server-side block item
     * @param context context it is sent in, or null for none
     */
    public static void pin(ItemStack out, BlockItem item, @Nullable PacketContext context) {
        var pin = get(item, context).pin();
        if (pin != null) {
            out.set(DataComponents.BLOCK_STATE, pin);
        }
    }

    /**
     * The decision with every stand-in counted, not memoised, for reports.
     *
     * @param item    server-side block item
     * @param context context it is sent in, or null for none
     */
    public static Survey survey(BlockItem item, @Nullable PacketContext context) {
        return decide(item, context, Integer.MAX_VALUE);
    }

    /**
     * Marks client states that draw nothing, so no item pins to them. Polymer's textured blocks
     * register their empty-model states here; vanilla's invisible render shape needs no entry.
     */
    public static void registerInvisibleState(Predicate<BlockState> predicate) {
        INVISIBLE.add(predicate);
        CACHE.clear();
    }

    /**
     * Marks sounds a client in this context never plays itself, such as a sound the server took
     * over and the context's resource pack mutes. A stand-in whose place sound is muted may pin
     * even where the real block sounds different.
     */
    public static void registerMutedSound(BiPredicate<SoundEvent, PacketContext> predicate) {
        MUTED.add(predicate);
        CACHE.clear();
    }

    /**
     * Marks client states a client's own block updates can change, such as note blocks outside a
     * stable slot, for {@link #PIN_UNSTABLE}.
     */
    public static void registerUnstableState(Predicate<BlockState> predicate) {
        UNSTABLE.add(predicate);
        CACHE.clear();
    }

    /** True when something registered through {@link #registerUnstableState} marks this state. */
    public static boolean isUnstable(BlockState state) {
        for (var predicate : UNSTABLE) {
            if (predicate.test(state)) {
                return true;
            }
        }
        return false;
    }

    /** True when something registered through {@link #registerMutedSound} mutes this sound. */
    public static boolean isMuted(SoundEvent sound, @Nullable PacketContext context) {
        for (var predicate : MUTED) {
            if (predicate.test(sound, context)) {
                return true;
            }
        }
        return false;
    }

    /** True when the client draws nothing for this state. */
    public static boolean isInvisible(BlockState state) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }
        for (var predicate : INVISIBLE) {
            if (predicate.test(state)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this context has Polymer's main resource pack; false without the module. */
    public static boolean hasMainPack(@Nullable PacketContext context) {
        return CompatStatus.POLYMER_RESOURCE_PACK && PolymerResourcePackUtils.hasMainPack(context);
    }

    // Walks the block's states through the context's mapper, stopping once the placement states show
    // `limit` distinct stand-ins.
    private static Survey decide(BlockItem item, @Nullable PacketContext context, int limit) {
        var block = item.getBlock();
        var all = new LinkedHashSet<BlockState>();
        var placement = new LinkedHashSet<BlockState>();
        var defaultState = block.defaultBlockState();
        for (var state : block.getStateDefinition().getPossibleStates()) {
            var standIn = PolymerBlockUtils.getPolymerBlockState(state, context);
            all.add(standIn);
            if (isPlacementState(state, defaultState)) {
                placement.add(standIn);
            }
            if (placement.size() >= limit) {
                break;
            }
        }
        var blocks = new LinkedHashSet<Block>();
        for (var standIn : all) {
            blocks.add(standIn.getBlock());
        }
        if (placement.size() != 1) {
            return new Survey(new Decision(Items.FIREWORK_STAR, null, null, placement.size(), Failure.SECOND_STAND_IN), all.size(), blocks);
        }
        var standIn = placement.iterator().next();
        var failure = test(block, standIn, context);
        if (failure != null) {
            return new Survey(new Decision(Items.FIREWORK_STAR, null, standIn, 1, failure), all.size(), blocks);
        }
        var pin = BlockItemStateProperties.EMPTY;
        for (var property : standIn.getProperties()) {
            pin = pin.with(property, standIn);
        }
        return new Survey(new Decision(standIn.getBlock().asItem(), pin, standIn, 1, null), all.size(), blocks);
    }

    private static boolean isPlacementState(BlockState state, BlockState defaultState) {
        for (var property : state.getProperties()) {
            if (LATER_PROPERTIES.contains(property.getName()) && !state.getValue(property).equals(defaultState.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    // The four tests a single stand-in has to pass before a client may place it itself.
    private static @Nullable Failure test(Block real, BlockState standIn, @Nullable PacketContext context) {
        if (isInvisible(standIn)) {
            return Failure.INVISIBLE;
        }
        var standInItem = standIn.getBlock().asItem();
        if (standInItem.getClass() != BlockItem.class || ((BlockItem) standInItem).getBlock() != standIn.getBlock()) {
            return Failure.NOT_PLAIN_ITEM;
        }
        if (hasSurvivalRule(standIn.getBlock())) {
            return Failure.SURVIVAL_RULE;
        }
        var place = standIn.getSoundType().getPlaceSound();
        if (!isMuted(place, context)) {
            for (var state : real.getStateDefinition().getPossibleStates()) {
                if (!state.getSoundType().getPlaceSound().location().equals(place.location())) {
                    return Failure.SOUND;
                }
            }
        }
        if (!PIN_UNSTABLE && isUnstable(standIn)) {
            return Failure.UNSTABLE_SLOT;
        }
        return null;
    }

    // A block that overrides canSurvive can refuse a placement on the client that the server allows.
    private static boolean hasSurvivalRule(Block block) {
        return SURVIVAL_RULES.computeIfAbsent(block.getClass(), type -> {
            for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("canSurvive", BlockState.class, LevelReader.class, BlockPos.class);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // declared further up, or not at all
                }
            }
            return false;
        });
    }

    private record Key(Block block, boolean pack) {
    }

    /** The first test a block failed, in the order they run. */
    public enum Failure {
        /** Its states show more than one stand-in. */
        SECOND_STAND_IN,
        /** The stand-in draws nothing. */
        INVISIBLE,
        /** The stand-in's block has no plain {@link BlockItem} placing it. */
        NOT_PLAIN_ITEM,
        /** The stand-in's block overrides {@code canSurvive}. */
        SURVIVAL_RULE,
        /** The client would play the stand-in's place sound, which differs from the real block's. */
        SOUND,
        /** The stand-in is a note block outside a stable slot and {@link #PIN_UNSTABLE} is off. */
        UNSTABLE_SLOT
    }

    /**
     * @param item     the client item
     * @param pin      every property of the stand-in, or null for the firework star
     * @param standIn  the one stand-in every placement state shows, or null when there are several
     * @param standIns distinct stand-ins over the placement states; {@link #get} stops counting at two
     * @param failure  the first test the block failed, or null when it is pinned
     */
    public record Decision(Item item, @Nullable BlockItemStateProperties pin, @Nullable BlockState standIn,
                           int standIns, @Nullable Failure failure) {
        public boolean pinned() {
            return this.pin != null;
        }
    }

    /**
     * @param decision      the decision, with every placement state counted
     * @param allStandIns   distinct stand-ins over every state, the strict count
     * @param standInBlocks the blocks those stand-ins belong to
     */
    public record Survey(Decision decision, int allStandIns, Set<Block> standInBlocks) {
        /** True when only states the world makes later, such as lit or powered, add stand-ins. */
        public boolean laterStatesOnly() {
            return this.allStandIns > 1 && this.decision.standIns() == 1;
        }
    }
}
