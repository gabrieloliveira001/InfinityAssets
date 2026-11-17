package net.byebye.infinityAssets.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.byebye.infinityAssets.content.*;
import net.byebye.infinityAssets.util.FileUtil;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * Builds output/generated.zip from every contents/&lt;folder&gt;/resourcepack plus the generated JSON
 * (item definitions, models, equipment, sounds, fonts, atlases). Independent of the running server.
 */
public final class PackGenerator {
    public static final int PACK_FORMAT = 46; // 1.21.4
    private static final char FIRST_GLYPH = '';

    private final File dataFolder;
    private final Logger logger;

    public record Options(List<String> ignoredExtensions, List<File> extraFolders, String description,
                          boolean customMenu, boolean hideInventoryLabel) {
        public Options(List<String> ignoredExtensions, List<File> extraFolders, String description) {
            this(ignoredExtensions, extraFolders, description, true, true);
        }
    }

    public record Result(File zip, int problems) {
    }

    public PackGenerator(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public Result generate(ContentRegistry registry, Options options) throws IOException {
        File work = new File(dataFolder, "storage/pack_build");
        FileUtil.deleteRecursively(work);
        work.mkdirs();

        // 1. raw assets of each content folder (priority order = registry order), then uploads/other plugins
        for (File folder : registry.contentFolders()) copyContentAssets(folder, registry.namespaceOf(folder), work);
        File uploads = new File(dataFolder, "uploads");
        uploads.mkdirs();
        File[] uploaded = uploads.listFiles();
        if (uploaded != null) {
            Arrays.sort(uploaded);
            for (File f : uploaded) mergeExternal(f, work);
        }
        for (File extra : options.extraFolders()) {
            if (extra.exists()) {
                logger.info("Mesclando resource pack externo: " + extra.getPath());
                mergeExternal(extra, work);
            }
        }

        // 2. generated content
        writePackMcmeta(work, options.description());
        for (CustomItem item : registry.items()) writeItem(work, item);
        for (EquipmentDef e : registry.equipments().values()) writeEquipment(work, e);
        writeSounds(work, registry);
        writeFonts(work, registry);
        if (options.customMenu()) net.byebye.infinityAssets.menu.MenuTheme.writePack(work, options.hideInventoryLabel());

        AtlasSources atlas = AtlasSources.scan(work);
        atlas.write(work);

        // 3. validation (warnings only) and zip
        List<String> problems = new PackValidator(work).validate(atlas);
        int shown = 0;
        for (String p : problems) {
            if (shown++ >= 40) {
                logger.warning("... e mais " + (problems.size() - 40) + " problemas no resource pack.");
                break;
            }
            logger.warning("[pack] " + p);
        }

        File output = new File(dataFolder, "output");
        output.mkdirs();
        File zip = new File(output, "generated.zip");
        File tmpZip = new File(output, "generated.zip.tmp");
        FileUtil.zipDirectory(work, tmpZip, options.ignoredExtensions());
        Files.move(tmpZip.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        FileUtil.deleteRecursively(work);
        return new Result(zip, problems.size());
    }

    private static final Set<String> ASSET_DIRS = Set.of("textures", "models", "sounds", "font", "lang", "shaders",
            "blockstates", "particles", "texts", "atlases", "equipment", "items", "post_effect");

    /**
     * Supports every folder layout ItemsAdder accepts for contents/&lt;folder&gt;:
     * resourcepack/assets/&lt;ns&gt;, resourcepack/&lt;ns&gt;, assets/&lt;ns&gt;, &lt;ns&gt;/... and the short
     * layout textures/, models/, sounds/ directly in the folder (namespace = info.namespace).
     */
    private void copyContentAssets(File folder, String ns, File work) throws IOException {
        File assetsOut = new File(work, "assets");
        File rp = new File(folder, "resourcepack");
        if (rp.isDirectory()) {
            File[] children = rp.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) {
                        FileUtil.copyTree(c, c.getName().equals("assets") ? assetsOut : new File(assetsOut, c.getName()));
                    } else if (c.getName().equals("pack.png")) {
                        Files.copy(c.toPath(), new File(work, "pack.png").toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        File assets = new File(folder, "assets");
        if (assets.isDirectory()) FileUtil.copyTree(assets, assetsOut);
        File self = new File(folder, folder.getName());
        if (self.isDirectory() && !ASSET_DIRS.contains(self.getName()) && !self.getName().equals("configs")) {
            FileUtil.copyTree(self, new File(assetsOut, folder.getName()));
        }
        for (String dir : ASSET_DIRS) {
            File d = new File(folder, dir);
            if (d.isDirectory()) FileUtil.copyTree(d, new File(assetsOut, ns + "/" + dir));
        }
    }

    private void mergeExternal(File f, File work) throws IOException {
        if (f.isDirectory()) {
            // accept both "<folder>/assets/..." and a folder that *is* a pack root
            FileUtil.copyTree(f, work);
        } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            FileUtil.unzip(f, work);
        }
    }

    private void writePackMcmeta(File work, String description) throws IOException {
        JsonObject pack = Json.obj("pack_format", PACK_FORMAT,
                "supported_formats", Json.obj("min_inclusive", PACK_FORMAT, "max_inclusive", 999),
                "min_format", PACK_FORMAT, "max_format", 999,
                "description", description);
        Json.write(new File(work, "pack.mcmeta"), Json.obj("pack", pack));
    }

    // ------------------------------------------------------------------ items

    private void writeItem(File work, CustomItem item) throws IOException {
        if (item.itemModel != null) {
            item.resolvedModel = item.itemModel; // expert override: the user ships the item definition
            return;
        }
        String model;
        if (!item.stateModels.isEmpty()) {
            model = item.stateModels.getOrDefault("normal", item.stateModels.values().iterator().next());
        } else if (item.generate && (!item.textures.isEmpty() || !item.layerTextures.isEmpty())) {
            model = item.namespace + ":item/" + item.id;
            if (modelFile(work, model).exists()) {
                // never overwrite a hand-made model with the same name
                model = item.namespace + ":item/ia_generated/" + item.id;
            }
            writeGeneratedModels(work, item, model);
        } else if (item.modelPath != null) {
            model = item.modelPath;
        } else {
            model = "minecraft:item/" + item.material.getKey().getKey();
        }
        item.resolvedModel = model;

        // graphics.models states (pulling_0, cast, ...) mapped onto the suffix convention of ItemDefinitions
        Map<String, String> states = new HashMap<>();
        item.stateModels.forEach((state, ref) -> states.put(suffixFor(state), ref));
        final String base = model;
        JsonObject def = ItemDefinitions.build(item.material, model, suffix -> {
            String ref = states.get(suffix);
            if (ref != null) return ref;
            String candidate = base + suffix;
            return modelFile(work, candidate).exists() ? candidate : null;
        });
        Json.write(new File(work, "assets/" + item.namespace + "/items/" + item.id + ".json"), def);
    }

    /** IA graphics state -> ItemsAdder file suffix (pulling_0 -> _0, cast -> _cast, arrow -> _charged ...). */
    private static String suffixFor(String state) {
        return switch (state) {
            case "pulling_0" -> "_0";
            case "pulling_1" -> "_1";
            case "pulling_2" -> "_2";
            case "arrow" -> "_charged";
            case "rocket" -> "_firework";
            default -> "_" + state;
        };
    }

    private void writeGeneratedModels(File work, CustomItem item, String model) throws IOException {
        String parent = item.parent != null ? item.parent
                : isHandheld(item.material) ? "minecraft:item/handheld" : "minecraft:item/generated";
        if (!parent.contains(":")) parent = "minecraft:" + parent;

        if (!item.layerTextures.isEmpty() && item.layerTextures.keySet().stream().noneMatch(PackGenerator::isState)) {
            // explicit layers, e.g. parent: block/cross + textures: {cross: ...} or per-face block textures
            JsonObject t = new JsonObject();
            item.layerTextures.forEach(t::addProperty);
            Json.write(modelFile(work, model), Json.obj("parent", parent, "textures", t));
            return;
        }

        Map<String, String> byState = new LinkedHashMap<>();
        if (!item.layerTextures.isEmpty()) {
            item.layerTextures.forEach((state, tex) -> byState.put(state.equals("normal") ? "" : suffixFor(state), tex));
        } else {
            // legacy resource.textures list: [normal, then the animation states in ItemsAdder order]
            List<String> suffixes = switch (item.material) {
                case BOW -> List.of("", "_0", "_1", "_2");
                case CROSSBOW -> List.of("", "_0", "_1", "_2", "_charged", "_firework");
                case SHIELD -> List.of("", "_blocking");
                case FISHING_ROD -> List.of("", "_cast");
                default -> List.of("");
            };
            if (suffixes.size() == 1 && item.textures.size() > 1) {
                // several layers (e.g. leather overlay): layer0, layer1, ...
                JsonObject t = new JsonObject();
                for (int i = 0; i < item.textures.size(); i++) t.addProperty("layer" + i, item.textures.get(i));
                Json.write(modelFile(work, model), Json.obj("parent", parent, "textures", t));
                return;
            }
            for (int i = 0; i < item.textures.size() && i < suffixes.size(); i++) byState.put(suffixes.get(i), item.textures.get(i));
        }
        for (Map.Entry<String, String> e : byState.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("layer0", e.getValue());
            Json.write(modelFile(work, model + e.getKey()), Json.obj("parent", parent, "textures", t));
        }
    }

    private static boolean isState(String key) {
        return key.equals("normal") || key.startsWith("pulling_") || key.equals("cast") || key.equals("blocking")
                || key.equals("arrow") || key.equals("rocket") || key.equals("throwing");
    }

    private static boolean isHandheld(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.equals("FISHING_ROD") || n.equals("STICK") || n.equals("BLAZE_ROD")
                || n.equals("MACE") || n.equals("TRIDENT");
    }

    static File modelFile(File work, String ref) {
        String[] p = split(ref);
        return new File(work, "assets/" + p[0] + "/models/" + p[1] + ".json");
    }

    static File textureFile(File work, String ref) {
        String[] p = split(ref);
        return new File(work, "assets/" + p[0] + "/textures/" + p[1] + ".png");
    }

    private static String[] split(String ref) {
        int i = ref.indexOf(':');
        return i < 0 ? new String[]{"minecraft", ref} : new String[]{ref.substring(0, i), ref.substring(i + 1)};
    }

    // ------------------------------------------------------------------ equipment (worn armor)

    private void writeEquipment(File work, EquipmentDef e) throws IOException {
        JsonObject layers = new JsonObject();
        String texId = e.namespace() + ":" + e.id();
        if (e.layer1() != null && copyTexture(work, e.layer1(), "entity/equipment/humanoid/" + e.id(), e.namespace())) {
            layers.add("humanoid", Json.arr(Json.obj("texture", texId)));
        }
        if (e.layer2() != null && copyTexture(work, e.layer2(), "entity/equipment/humanoid_leggings/" + e.id(), e.namespace())) {
            layers.add("humanoid_leggings", Json.arr(Json.obj("texture", texId)));
        }
        if (layers.size() == 0) return;
        Json.write(new File(work, "assets/" + e.namespace() + "/equipment/" + e.id() + ".json"), Json.obj("layers", layers));
    }

    private boolean copyTexture(File work, String ref, String destPath, String ns) throws IOException {
        File src = textureFile(work, ref);
        if (!src.exists()) {
            logger.warning("[pack] Textura de armadura nao encontrada: " + ref);
            return false;
        }
        File dst = new File(work, "assets/" + ns + "/textures/" + destPath + ".png");
        dst.getParentFile().mkdirs();
        if (!dst.getCanonicalFile().equals(src.getCanonicalFile())) {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File meta = new File(src.getPath() + ".mcmeta");
            if (meta.exists()) Files.copy(meta.toPath(), new File(dst.getPath() + ".mcmeta").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    // ------------------------------------------------------------------ sounds

    private void writeSounds(File work, ContentRegistry registry) throws IOException {
        Map<String, JsonObject> byNs = new TreeMap<>();
        // IA behaviour: every .ogg under assets/<ns>/sounds is registered as "<ns>:<path.with.dots>"
        File assets = new File(work, "assets");
        File[] namespaces = assets.listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File nsDir : namespaces) {
                if (nsDir.getName().equals("minecraft")) continue;
                File soundsDir = new File(nsDir, "sounds");
                for (File ogg : FileUtil.listFilesRecursively(soundsDir, ".ogg")) {
                    String rel = soundsDir.toPath().relativize(ogg.toPath()).toString().replace(File.separatorChar, '/');
                    rel = rel.substring(0, rel.length() - 4);
                    JsonObject json = byNs.computeIfAbsent(nsDir.getName(), k -> existingSounds(work, k));
                    String name = rel.replace('/', '.');
                    if (!json.has(name)) {
                        json.add(name, Json.obj("sounds", Json.arr(nsDir.getName() + ":" + rel)));
                    }
                }
            }
        }
        // explicit "sounds:" config entries override auto-registered ones
        for (SoundDef s : registry.sounds()) {
            JsonObject json = byNs.computeIfAbsent(s.namespace(), k -> existingSounds(work, k));
            String path = s.path().contains(":") ? s.path() : s.namespace() + ":" + s.path();
            JsonObject sound = Json.obj("name", path, "stream", s.stream() ? Boolean.TRUE : null,
                    "volume", s.volume(), "pitch", s.pitch(), "attenuation_distance", s.attenuationDistance());
            json.add(s.name(), Json.obj("sounds", Json.arr(sound), "subtitle", s.subtitle()));
        }
        for (Map.Entry<String, JsonObject> e : byNs.entrySet()) {
            Json.write(new File(work, "assets/" + e.getKey() + "/sounds.json"), e.getValue());
        }
    }

    private static JsonObject existingSounds(File work, String ns) {
        File f = new File(work, "assets/" + ns + "/sounds.json");
        JsonObject o = f.exists() ? Json.read(f) : null;
        return o == null ? new JsonObject() : o;
    }

    // ------------------------------------------------------------------ font images

    /**
     * Gives every font_image a stable private-use glyph (explicit "symbol:" wins). Stored in
     * storage/font_images_cache.yml so emojis keep their character between builds. Call after every load.
     */
    public static void assignGlyphs(File dataFolder, ContentRegistry registry) {
        if (registry.fontImages().isEmpty()) return;
        File cacheFile = new File(dataFolder, "storage/font_images_cache.yml");
        YamlConfiguration cache = YamlConfiguration.loadConfiguration(cacheFile);
        Set<Integer> used = new HashSet<>();
        for (String k : cache.getKeys(false)) used.add(cache.getInt(k));
        for (FontImage fi : registry.fontImages().values()) if (fi.glyph != 0) used.add((int) fi.glyph);
        int next = FIRST_GLYPH;
        boolean dirty = false;
        for (FontImage fi : registry.fontImages().values()) {
            if (fi.glyph != 0) continue;
            String key = fi.namespacedId().replace(':', '_').replace('.', '_');
            if (cache.isInt(key)) {
                fi.glyph = (char) cache.getInt(key);
            } else {
                while (used.contains(next)) next++;
                fi.glyph = (char) next;
                used.add(next);
                cache.set(key, next);
                dirty = true;
            }
        }
        if (dirty) {
            try {
                cacheFile.getParentFile().mkdirs();
                cache.save(cacheFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void writeFonts(File work, ContentRegistry registry) throws IOException {
        if (registry.fontImages().isEmpty()) return;
        assignGlyphs(dataFolder, registry);
        File fontFile = new File(work, "assets/minecraft/font/default.json");
        JsonObject font = fontFile.exists() ? Json.read(fontFile) : null;
        if (font == null) font = new JsonObject();
        JsonArray providers = font.has("providers") && font.get("providers").isJsonArray()
                ? font.getAsJsonArray("providers") : new JsonArray();
        for (FontImage fi : registry.fontImages().values()) {
            File png = textureFile(work, fi.path);
            if (!png.exists()) {
                logger.warning("[pack] Textura da font_image '" + fi.namespacedId() + "' nao encontrada: " + fi.path);
                continue;
            }
            int height = fi.scaleRatio > 0 ? fi.scaleRatio : pngHeight(png);
            int ascent = Math.min(fi.yPosition, height); // the client rejects ascent > height
            providers.add(Json.obj("type", "bitmap", "file", fi.path + ".png", "ascent", ascent,
                    "height", height, "chars", Json.arr(String.valueOf(fi.glyph))));
        }
        font.add("providers", providers);
        Json.write(fontFile, font);
    }

    private static int pngHeight(File png) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(png))) {
            in.skipBytes(20); // signature(8) + IHDR length/type(8) + width(4)
            return Math.max(1, Math.min(256, in.readInt()));
        } catch (IOException e) {
            return 9;
        }
    }
}
