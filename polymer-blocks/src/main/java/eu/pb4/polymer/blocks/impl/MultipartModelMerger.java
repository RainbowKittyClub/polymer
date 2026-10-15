package eu.pb4.polymer.blocks.impl;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.common.impl.CommonImpl;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A multipart blockstate has one particle for every state of its block, taken from its first case, so
 * blocks sharing one vanilla block through {@link MultiPolymerBlockModel} would all break with the same
 * particle. This merges the parts of one state into a single model, which can be a variant of its own.
 */
public final class MultipartModelMerger {
    private static final int MAX_PARENT_DEPTH = 32;

    private MultipartModelMerger() {}

    /**
     * Returns a variant drawing the same as the given parts, adding the merged model to the pack, or null
     * when one model can't: weighted parts, parts with different rotations, a part whose uvlock differs on
     * a face the rotation turns, mixed ambient occlusion, or a model without elements.
     */
    public static PolymerBlockModel @Nullable [] merge(ResourcePackBuilder builder, MultiPolymerBlockModel model) {
        try {
            return mergeParts(builder, model);
        } catch (RuntimeException e) {
            // A malformed model; the multipart cases still draw it as before.
            return null;
        }
    }

    private static PolymerBlockModel @Nullable [] mergeParts(ResourcePackBuilder builder, MultiPolymerBlockModel model) {
        var parts = model.models();
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        if (parts.isEmpty()) {
            return null;
        }
        for (var part : parts) {
            if (part.length != 1) {
                return null;
            }
        }

        var first = parts.getFirst()[0];
        var textures = new JsonObject();
        var elements = new JsonArray();
        var ambientOcclusion = true;
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i)[0];
            if (part.x() != first.x() || part.y() != first.y()) {
                return null;
            }
            var resolved = resolve(builder, part.model());
            if (resolved == null) {
                return null;
            }
            if (part.uvLock() != first.uvLock() && turnsUv(first, resolved.elements())) {
                return null;
            }
            if (i == 0) {
                ambientOcclusion = resolved.ambientOcclusion();
            } else if (resolved.ambientOcclusion() != ambientOcclusion) {
                return null;
            }

            var prefix = "p" + i + "_";
            resolved.textures().forEach((key, value) -> textures.add(prefix + key, value.isJsonPrimitive()
                    && value.getAsString().startsWith("#") ? new JsonPrimitive(rename(value.getAsString(), prefix)) : value));
            for (var element : resolved.elements()) {
                var copy = element.getAsJsonObject().deepCopy();
                if (copy.get("faces") instanceof JsonObject faces) {
                    for (var face : faces.entrySet()) {
                        var faceObject = face.getValue().getAsJsonObject();
                        if (faceObject.has("texture")) {
                            faceObject.addProperty("texture", rename(faceObject.get("texture").getAsString(), prefix));
                        }
                    }
                }
                elements.add(copy);
            }
        }
        if (textures.has("p0_particle")) {
            textures.addProperty("particle", "#p0_particle");
        }

        var json = new JsonObject();
        if (!ambientOcclusion) {
            json.addProperty("ambientocclusion", false);
        }
        json.add("textures", textures);
        json.add("elements", elements);
        var bytes = CommonImpl.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);

        // Named after its content, so the same parts always give the same model whatever the request order.
        var hash = Hashing.sha1().hashBytes(bytes).toString().substring(0, 8);
        var id = Identifier.fromNamespaceAndPath("polymer", "block/merged/" + first.model().getNamespace() + "/" + first.model().getPath() + "_" + hash);
        builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json", bytes);
        return new PolymerBlockModel[]{ PolymerBlockModel.of(id, first.x(), first.y(), first.uvLock(), 1) };
    }

    // UV lock only changes faces the rotation turns within their own plane: every face for an x rotation,
    // only up and down for a y rotation, none without one.
    private static boolean turnsUv(PolymerBlockModel rotation, JsonArray elements) {
        if (rotation.x() != 0) {
            return true;
        }
        if (rotation.y() == 0) {
            return false;
        }
        for (var element : elements) {
            if (element.getAsJsonObject().get("faces") instanceof JsonObject faces && (faces.has("up") || faces.has("down"))) {
                return true;
            }
        }
        return false;
    }

    private static String rename(String reference, String prefix) {
        return "#" + prefix + (reference.startsWith("#") ? reference.substring(1) : reference);
    }

    @Nullable
    private static Resolved resolve(ResourcePackBuilder builder, Identifier id) {
        var textures = new LinkedHashMap<String, JsonElement>();
        JsonArray elements = null;
        Boolean ambientOcclusion = null;
        var next = id;
        for (int depth = 0; ; depth++) {
            if (depth == MAX_PARENT_DEPTH) {
                return null;
            }
            var data = builder.getDataOrSource("assets/" + next.getNamespace() + "/models/" + next.getPath() + ".json");
            if (data == null) {
                return null;
            }
            var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("textures") instanceof JsonObject modelTextures) {
                modelTextures.entrySet().forEach(entry -> textures.putIfAbsent(entry.getKey(), entry.getValue()));
            }
            if (elements == null && json.get("elements") instanceof JsonArray modelElements) {
                elements = modelElements;
            }
            if (ambientOcclusion == null && json.has("ambientocclusion")) {
                ambientOcclusion = json.get("ambientocclusion").getAsBoolean();
            }
            if (!json.has("parent")) {
                break;
            }
            next = Identifier.tryParse(json.get("parent").getAsString());
            if (next == null) {
                return null;
            }
        }
        if (elements == null) {
            return null;
        }
        return new Resolved(textures, elements, ambientOcclusion == null || ambientOcclusion);
    }

    private record Resolved(Map<String, JsonElement> textures, JsonArray elements, boolean ambientOcclusion) {}
}
