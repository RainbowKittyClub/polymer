package eu.pb4.polymer.blocks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import eu.pb4.polymer.blocks.impl.BlockExtBlockMapper;
import eu.pb4.polymer.blocks.impl.DefaultModelData;
import eu.pb4.polymer.blocks.impl.MultipartModelMerger;
import eu.pb4.polymer.blocks.impl.PolymerBlocksInternal;
import eu.pb4.polymer.blocks.impl.VanillaBlockPropertiesPredicate;
import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.core.impl.PolymerImpl;
import eu.pb4.polymer.resourcepack.api.ResourcePackCreator;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

public final class BlockResourceCreator {
    private static final PolymerBlockModel EMPTY = PolymerBlockModel.of(Identifier.fromNamespaceAndPath("polymer", "block/empty"));
    // What requests without a predicate pass, so FULL_BLOCK can tell them from predicate requests.
    static final Predicate<BlockState> ANY_STATE = x -> true;
    private final Map<BlockModelType, List<BlockState>> states;
    private final Set<Block> hasRequested = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<BlockState, Either<PolymerBlockModel[], MultiPolymerBlockModel>> models;
    private final ResourcePackCreator creator;
    private final Runnable onRegister;
    private final BlockExtBlockMapper blockMapper;

    private final EnumMap<BlockModelType, BlockState> emptyBlocks = new EnumMap<>(BlockModelType.class);

    private boolean registered = false;
    private boolean registeredEmpty = false;
    private boolean warnedPastSnare = false;

    public static BlockResourceCreator of(ResourcePackCreator creator) {
        return new BlockResourceCreator(creator, new BlockExtBlockMapper(BlockMapper.createDefault()), () -> {});
    }

    BlockResourceCreator(ResourcePackCreator creator, BlockExtBlockMapper blockMapper, Runnable onRegister) {
        this.states = new EnumMap<>(BlockModelType.class);
        DefaultModelData.USABLE_STATES.forEach((key, value) -> this.states.put(key, new ReferenceArrayList<>(value)));
        this.models = new IdentityHashMap<>();
        this.creator = creator;
        this.blockMapper = blockMapper;
        this.blockMapper.stateMap.putAll(DefaultModelData.OLDER_CLIENT_REMAPS);
        this.onRegister = onRegister;
    }

    public BlockMapper getBlockMapper() {
        return this.blockMapper;
    }

    private void registerEvent() {
        if (!this.registered) {
            creator.creationEvent.register((b) -> {
                if (b instanceof DefaultRPBuilder<?> defaultRPBuilder) {
                    defaultRPBuilder.buildEvent.register(c -> this.generateResources(defaultRPBuilder, c));
                }
            });
            this.onRegister.run();
            this.registered = true;
        }
    }

    @Nullable
    public BlockState requestEmpty(BlockModelType type) {
        var x = this.emptyBlocks.get(type);
        if (x != null) {
            return x;
        }
        Predicate<BlockState> predicate = null;
        if (type.name().contains("TRAPDOOR")) {
            predicate = b -> b.is(Blocks.IRON_TRAPDOOR);
        } else if (type.name().contains("DOOR")) {
            predicate = b -> b.is(Blocks.IRON_DOOR);
        }  else if (type == BlockModelType.VINES) {
            predicate = b -> b.is(Blocks.TWISTING_VINES);
        }

        if (predicate != null) {
            x = requestBlockImpl(type, predicate, true, Either.left(new PolymerBlockModel[]{EMPTY}));
        }
        if (x == null) {
            x = requestBlockImpl(type, _ -> true, true, Either.left(new PolymerBlockModel[]{EMPTY}));
        }
        if (x == null) {
            return null;
        }
        this.emptyBlocks.put(type, x);
        if (!this.registeredEmpty) {
            this.registeredEmpty = true;
            this.creator.addAssetSource("polymer-blocks");
        }
        return x;
    }

    @Nullable
    public BlockState requestBlock(BlockModelType type, PolymerBlockModel... model) {
        return requestBlock(type, ANY_STATE, model);
    }

    public BlockState requestBlock(BlockModelType type, Predicate<BlockState> predicate, PolymerBlockModel... model) {
        return requestBlockImpl(type, predicate, false, Either.left(model));
    }

    @Nullable
    public BlockState requestBlock(BlockModelType type, MultiPolymerBlockModel model) {
        return requestBlock(type, ANY_STATE, model);
    }

    public BlockState requestBlock(BlockModelType type, Predicate<BlockState> predicate, MultiPolymerBlockModel model) {
        return requestBlockImpl(type, predicate, false, Either.right(model));
    }

    private BlockState requestBlockImpl(BlockModelType type, Predicate<BlockState> predicate, boolean reversed, Either<PolymerBlockModel[], MultiPolymerBlockModel> model) {
        var states = this.states.get(type);
        if (!states.isEmpty()) {
            if (reversed) {
                states = states.reversed();
            }

            BlockState state = null;
            for (var s : states) {
                if (predicate.test(s)) {
                    state = s;
                    break;
                }
            }
            if (state == null) {
                return null;
            }
            if (predicate == ANY_STATE && type == BlockModelType.FULL_BLOCK && !this.warnedPastSnare
                    && !(state.is(Blocks.NOTE_BLOCK) && state.getValue(NoteBlock.INSTRUMENT) == NoteBlockInstrument.SNARE)) {
                this.warnedPastSnare = true;
                PolymerImpl.LOGGER.warn("FULL_BLOCK requests without a predicate have used every snare note block, so {} and later ones take states predicate requests may rely on; which block gets which now depends on load order", state);
            }
            states.remove(state);
            models.put(state, model);
            if (state.is(Blocks.NOTE_BLOCK)) {
                this.onNoteBlockRequested();
            }
            if (state.getBlock() instanceof LeavesBlock) {
                this.onLeavesRequested(state.getBlock());
            }
            this.hasRequested.add(state.getBlock());
            this.registerEvent();

            if (state.getBlock() instanceof SimpleWaterloggedBlock) {
                this.blockMapper.stateMap.put(state, DefaultModelData.SPECIAL_REMAPS
                        .getOrDefault(state, (state.getBlock() instanceof LeavesBlock
                                ? state.getBlock().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true) : state.getBlock().defaultBlockState()).setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED)))
                );
            } else {
                this.blockMapper.stateMap.put(state, DefaultModelData.SPECIAL_REMAPS
                        .getOrDefault(state, state.getBlock() instanceof LeavesBlock
                                ? state.getBlock().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true) : state.getBlock().defaultBlockState())
                );
            }

            return state;
        }
        return null;
    }

    /**
     * Number of slots {@link #requestStableBlock(int, PolymerBlockModel...)} accepts.
     */
    public int getStableSlotCount() {
        return DefaultModelData.STABLE_NOTE_BLOCK_SLOTS.size();
    }

    /**
     * Requests a full block whose client-side state keeps its model through a vanilla client's own
     * block updates (breaking, placing, a bucket or a door next to it) while the block below is wood,
     * air or stone. Over a block with another instrument, such as sand or glass, or under a mob head,
     * it still changes. The state depends only on the slot, never on the order of requests, and
     * other requests never get it.
     *
     * @param slot from 0 to {@link #getStableSlotCount()} - 1
     * @return the state, or null when the slot is taken or out of range
     */
    @Nullable
    public BlockState requestStableBlock(int slot, PolymerBlockModel... model) {
        return requestStableBlockImpl(slot, Either.left(model));
    }

    @Nullable
    public BlockState requestStableBlock(int slot, MultiPolymerBlockModel model) {
        return requestStableBlockImpl(slot, Either.right(model));
    }

    @Nullable
    private BlockState requestStableBlockImpl(int slot, Either<PolymerBlockModel[], MultiPolymerBlockModel> model) {
        if (slot < 0 || slot >= getStableSlotCount()) {
            return null;
        }
        var states = DefaultModelData.STABLE_NOTE_BLOCK_SLOTS.get(slot);
        if (this.models.containsKey(states.getFirst())) {
            return null;
        }
        for (var state : states) {
            this.models.put(state, model);
        }
        this.onNoteBlockRequested();
        this.registerEvent();
        return states.getFirst();
    }

    // Real note blocks all look alike, so every one is sent as the default state. A client's own
    // updates then only move it within note 0 unpowered, which no request ever gets. On purpose, a
    // played note block's particle then always has note 0's colour; the server sends the sound, so
    // its pitch stays right.
    private void onNoteBlockRequested() {
        if (this.hasRequested.add(Blocks.NOTE_BLOCK)) {
            var defaultState = Blocks.NOTE_BLOCK.defaultBlockState();
            for (var state : Blocks.NOTE_BLOCK.getStateDefinition().getPossibleStates()) {
                if (state != defaultState) {
                    this.blockMapper.stateMap.put(state, defaultState);
                }
            }
        }
    }

    // Natural leaves all look alike, so every one is sent as persistent at distance 7. A client then only
    // sees persistent leaves, and placing or emptying a bucket from them keeps them persistent.
    private void onLeavesRequested(Block block) {
        if (this.hasRequested.add(block)) {
            var target = block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            for (var state : block.getStateDefinition().getPossibleStates()) {
                if (!state.getValue(LeavesBlock.PERSISTENT)) {
                    this.blockMapper.stateMap.put(state, target.setValue(LeavesBlock.WATERLOGGED, state.getValue(LeavesBlock.WATERLOGGED)));
                }
            }
        }
    }

    public int getBlocksLeft(BlockModelType type) {
        return this.states.get(type).size();
    }

    /** True when this state was handed out through {@link #requestEmpty}, so it draws nothing. */
    public boolean isEmptyModel(BlockState state) {
        var model = this.models.get(state);
        return model != null && model.left().filter(models -> models.length == 1 && EMPTY.equals(models[0])).isPresent();
    }

    public boolean hasRequestedEmpty(BlockModelType type) {
        return this.emptyBlocks.containsKey(type);
    }

    private void generateResources(DefaultRPBuilder builder, List<String> credits) {
        var keys = new HashSet<Map.Entry<String, Block>>();

        var variants = new HashMap<String, HashMap<String, JsonArray>>();
        var multipart = new HashMap<String, List<JsonObject>>();
        var bannedStates = new HashMap<String, JsonArray>();

        // this.models iterates in identity hash order, which changes every run.
        var sortedModels = new ArrayList<>(this.models.entrySet());
        sortedModels.sort(Comparator.comparingInt(entry -> Block.getId(entry.getKey())));

        for (var blockStateEntry : sortedModels) {
            if (!this.hasRequested.contains(blockStateEntry.getKey().getBlock())) {
                continue;
            }
            var state = blockStateEntry.getKey();
            var models = blockStateEntry.getValue();

            var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            var path = "assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json";
            keys.add(Map.entry(path, state.getBlock()));

            var banned = bannedStates.computeIfAbsent(path, (s) -> new JsonArray());
            var obj = new JsonObject();
            var el = new JsonArray();
            var selfMulti = new JsonObject();

            for (var prop : state.getProperties()) {
                var name = prop.getName();
                var obj2 = new JsonObject();
                //noinspection rawtypes,unchecked
                var value = ((Property) prop).getName(state.getValue(prop));
                obj2.addProperty(name,  "!" + value);
                selfMulti.addProperty(name, value);
                el.add(obj2);
            }
            obj.add("OR", el);
            banned.add(obj);

            if (models.left().isPresent()) {
                var stateName = PolymerBlocksInternal.generateStateName(state);
                var array = PolymerBlocksInternal.createJsonElement(models.left().orElseThrow());
                variants.computeIfAbsent(path, (s) -> new HashMap<>()).put(stateName, array);
            } else if (MultipartModelMerger.merge(builder, models.right().orElseThrow()) instanceof PolymerBlockModel[] merged) {
                var stateName = PolymerBlocksInternal.generateStateName(state);
                variants.computeIfAbsent(path, (s) -> new HashMap<>()).put(stateName, PolymerBlocksInternal.createJsonElement(merged));
            } else {
                for (var x : models.right().orElseThrow().models()) {
                    var mult = new JsonObject();
                    mult.add("when", selfMulti);
                    mult.add("apply", PolymerBlocksInternal.createJsonElement(x));
                    multipart.computeIfAbsent(path, (s) -> new ArrayList<>()).add(mult);
                }
            }

        }

        for (var keyVal : keys) {
            var key = keyVal.getKey();
            try {
                var modelObject = new JsonObject();

                var variantsObject = new JsonObject();
                var multipartObject = new JsonArray();

                if (variants.containsKey(key)) {
                    var values = new ArrayList<>(variants.get(key).entrySet());
                    values.sort(Map.Entry.comparingByKey());
                    for (var entries : values) {
                        variantsObject.add(entries.getKey(), entries.getValue());
                    }
                }


                if (multipart.containsKey(key)) {
                    var vanillaData = builder.getDataOrSource(key);
                    if (vanillaData != null) {
                        var vanillaJson = JsonParser.parseString(new String(vanillaData, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (vanillaJson.has("variants")) {
                            var values = new ArrayList<>(vanillaJson.get("variants").getAsJsonObject().entrySet());
                            values.sort(Map.Entry.comparingByKey());
                            for (var entries : values) {
                                var predicate = VanillaBlockPropertiesPredicate.parse(keyVal.getValue().getStateDefinition(), entries.getKey());

                                for (var state : keyVal.getValue().getStateDefinition().getPossibleStates()) {
                                    if (predicate.test(state) && !this.models.containsKey(state)) {
                                        variantsObject.add(PolymerBlocksInternal.generateStateName(state), entries.getValue());
                                    }
                                }
                            }
                        }
                        if (vanillaJson.has("multipart")) {
                            for (var entry : vanillaJson.get("multipart").getAsJsonArray()) {
                                var val = entry.getAsJsonObject().deepCopy();
                                var list = new JsonArray();
                                if (val.has("when")) {
                                    list.add(val.get("when"));
                                }

                                var ban2 = new JsonObject();
                                ban2.add("AND", bannedStates.get(key));
                                list.add(ban2);

                                var when = new JsonObject();
                                when.add("AND", list);

                                val.add("when", when);
                                multipartObject.add(val);
                            }
                        }
                    }

                    // A multipart model takes its particle from its first case, so the file's own cases go first.
                    multipart.get(key).forEach(multipartObject::add);
                }


                if (!variantsObject.isEmpty()) {
                    modelObject.add("variants", variantsObject);
                }

                if (!multipartObject.isEmpty()) {
                    modelObject.add("multipart", multipartObject);
                }

                builder.addData(key, DefaultRPBuilder.GSON.toJson(modelObject).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                PolymerImpl.LOGGER.warn("Exception occurred while building block model!", e);
            }
        }

    }
}
