package net.byebye.infinityAssets.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.byebye.infinityAssets.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Since 1.19.3 textures used by models must be listed in an atlas. Vanilla only stitches "block/" and "item/",
 * while ItemsAdder packs put textures anywhere under assets/&lt;ns&gt;/textures. Instead of moving files around
 * (which breaks fonts/GUIs) we scan every model and add the folders/files it uses to the atlases.
 */
public final class AtlasSources {
    private static final Set<String> VANILLA_STITCHED = Set.of("block", "item");

    private final Set<String> directories = new TreeSet<>(); // top-level folder names, shared by every namespace
    private final Set<String> singles = new TreeSet<>();     // "ns:file" textures in the textures root

    public static AtlasSources scan(File packRoot) {
        AtlasSources a = new AtlasSources();
        File assets = new File(packRoot, "assets");
        File[] namespaces = assets.listFiles(File::isDirectory);
        if (namespaces == null) return a;
        for (File nsDir : namespaces) {
            for (File model : FileUtil.listFilesRecursively(new File(nsDir, "models"), ".json")) {
                JsonObject json = Json.read(model);
                if (json == null || !json.has("textures") || !json.get("textures").isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("textures").entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    a.add(e.getValue().getAsString());
                }
            }
        }
        return a;
    }

    void add(String ref) {
        if (ref.startsWith("#")) return;
        String ns = "minecraft";
        String path = ref;
        int idx = ref.indexOf(':');
        if (idx >= 0) {
            ns = ref.substring(0, idx);
            path = ref.substring(idx + 1);
        }
        if (ns.equals("minecraft")) return;
        int slash = path.indexOf('/');
        if (slash < 0) {
            singles.add(ns + ":" + path);
        } else {
            String top = path.substring(0, slash);
            if (!VANILLA_STITCHED.contains(top)) directories.add(top);
        }
    }

    public boolean covers(String ref) {
        int idx = ref.indexOf(':');
        String ns = idx >= 0 ? ref.substring(0, idx) : "minecraft";
        String path = idx >= 0 ? ref.substring(idx + 1) : ref;
        if (ns.equals("minecraft")) return true;
        int slash = path.indexOf('/');
        if (slash < 0) return singles.contains(ns + ":" + path);
        String top = path.substring(0, slash);
        return VANILLA_STITCHED.contains(top) || directories.contains(top);
    }

    /** Writes blocks.json (&lt;=1.21.10 item textures) and items.json (1.21.11+ item atlas). Merges existing sources. */
    public void write(File packRoot) throws IOException {
        if (directories.isEmpty() && singles.isEmpty()) return;
        for (String atlas : List.of("blocks", "items")) {
            File f = new File(packRoot, "assets/minecraft/atlases/" + atlas + ".json");
            JsonObject root = f.exists() ? Json.read(f) : null;
            if (root == null) root = new JsonObject();
            JsonArray sources = root.has("sources") && root.get("sources").isJsonArray()
                    ? root.getAsJsonArray("sources") : new JsonArray();
            for (String dir : directories) {
                sources.add(Json.obj("type", "directory", "source", dir, "prefix", dir + "/"));
            }
            for (String single : singles) {
                sources.add(Json.obj("type", "single", "resource", single));
            }
            root.add("sources", sources);
            Json.write(f, root);
        }
    }
}
