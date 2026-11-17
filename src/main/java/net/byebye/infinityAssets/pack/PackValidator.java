package net.byebye.infinityAssets.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.byebye.infinityAssets.util.FileUtil;

import java.io.File;
import java.util.*;

/**
 * Checks the built pack folder for broken references (missing models, textures, parents, atlas coverage)
 * so problems show in the console instead of as purple/black squares in game.
 */
public final class PackValidator {
    private final File root;
    private final List<String> problems = new ArrayList<>();

    public PackValidator(File packRoot) {
        this.root = packRoot;
    }

    public List<String> validate(AtlasSources atlas) {
        File assets = new File(root, "assets");
        for (File def : FileUtil.listFilesRecursively(assets, ".json")) {
            String rel = rel(def);
            String[] p = rel.split("/", 3); // assets/<ns>/<rest>
            if (p.length < 3) continue;
            if (p[2].startsWith("items/")) checkItemDefinition(def, rel);
            else if (p[2].startsWith("models/")) checkModel(def, rel, atlas);
        }
        return problems;
    }

    private void checkItemDefinition(File def, String rel) {
        JsonObject json = Json.read(def);
        if (json == null) {
            problems.add(rel + ": JSON invalido");
            return;
        }
        walkItemModel(json.get("model"), rel);
    }

    private void walkItemModel(JsonElement el, String rel) {
        if (el == null || !el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";
        if (type.endsWith("model") && o.has("model") && o.get("model").isJsonPrimitive()) {
            String model = o.get("model").getAsString();
            if (!isVanilla(model) && !exists("models", model, ".json")) {
                problems.add(rel + ": modelo inexistente '" + model + "'");
            }
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonObject()) walkItemModel(v, rel);
            else if (v.isJsonArray()) for (JsonElement x : v.getAsJsonArray()) walkItemModel(x, rel);
        }
    }

    private void checkModel(File f, String rel, AtlasSources atlas) {
        JsonObject json = Json.read(f);
        if (json == null) {
            problems.add(rel + ": JSON invalido");
            return;
        }
        if (json.has("parent")) {
            String parent = json.get("parent").getAsString();
            if (!isVanilla(parent) && !exists("models", parent, ".json")) {
                problems.add(rel + ": parent inexistente '" + parent + "'");
            }
        }
        if (json.has("textures") && json.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("textures").entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String tex = e.getValue().getAsString();
                if (tex.startsWith("#")) continue;
                if (isVanilla(tex)) continue;
                if (!exists("textures", tex, ".png")) {
                    problems.add(rel + ": textura inexistente '" + tex + "'");
                } else if (atlas != null && !atlas.covers(tex)) {
                    problems.add(rel + ": textura fora do atlas '" + tex + "'");
                }
            }
        }
    }

    private static boolean isVanilla(String ref) {
        return !ref.contains(":") || ref.startsWith("minecraft:") || ref.startsWith("builtin/");
    }

    private boolean exists(String kind, String ref, String ext) {
        String ns = "minecraft";
        String path = ref;
        int idx = ref.indexOf(':');
        if (idx >= 0) {
            ns = ref.substring(0, idx);
            path = ref.substring(idx + 1);
        }
        return new File(root, "assets/" + ns + "/" + kind + "/" + path + ext).exists();
    }

    private String rel(File f) {
        return root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
    }
}
