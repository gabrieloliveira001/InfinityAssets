package net.byebye.infinityAssets.content;

import net.byebye.infinityAssets.util.FileUtil;
import net.byebye.infinityAssets.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads ItemsAdder-format content from plugins/InfinityAssets/contents/&lt;folder&gt;/configs/**.yml.
 * Pure data: no Bukkit server access, so it can also run offline (pack builds / tests).
 */
public final class ContentRegistry {
    /** Top-level sections that are read. Everything else is reported once as unsupported. */
    private static final Set<String> SUPPORTED = Set.of("info", "items", "categories", "recipes", "equipments",
            "armors_rendering", "legacy_armor_renderings", "font_images", "sounds", "dictionary", "minecraft_lang_overwrite");

    private final Logger logger;
    private final String dictionaryLang;

    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private final Map<String, CustomCategory> categories = new LinkedHashMap<>();
    private final Map<String, EquipmentDef> equipments = new LinkedHashMap<>();
    private final Map<String, FontImage> fontImages = new LinkedHashMap<>();
    private final List<SoundDef> sounds = new ArrayList<>();
    /** recipe kind (crafting_table, cooking, ...) -> list of (namespace, id, section) */
    private final List<RecipeEntry> recipes = new ArrayList<>();
    private final List<File> contentFolders = new ArrayList<>();
    private final Map<File, String> folderNamespaces = new HashMap<>();
    private final Map<String, String> dictionary = new HashMap<>();
    private final Set<String> unsupported = new TreeSet<>();
    /** Per-item features recognised but not implemented (custom blocks, events...), reported once. */
    private static final List<String> UNSUPPORTED_ITEM_KEYS = List.of("behaviours.block", "specific_properties.block",
            "behaviours.furniture_sit", "behaviours.vehicle", "behaviours.gun", "behaviours.music_disc",
            "behaviours.trampoline", "behaviours.connectable", "behaviours.bed", "behaviours.contact_damage",
            "events", "events_needed_player_stats", "jukebox_disc", "nbt", "components_nbt_file", "variant_of",
            "drop", "blocked_enchants", "specific_properties.fuel");
    private int errors;

    public record RecipeEntry(String namespace, String kind, String id, ConfigurationSection section, File file) {
    }

    public ContentRegistry(Logger logger, String dictionaryLang) {
        this.logger = logger;
        this.dictionaryLang = dictionaryLang == null ? "en" : dictionaryLang;
    }

    // ------------------------------------------------------------------ loading

    public void load(File contentsDir, List<String> priority) {
        items.clear();
        categories.clear();
        equipments.clear();
        fontImages.clear();
        sounds.clear();
        recipes.clear();
        contentFolders.clear();
        folderNamespaces.clear();
        dictionary.clear();
        unsupported.clear();
        errors = 0;

        File[] dirs = contentsDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        List<File> ordered = new ArrayList<>(Arrays.asList(dirs));
        // IA: folders listed in contents-folders-priority load first, so later folders override them.
        ordered.sort(Comparator.<File>comparingInt(f -> {
            int i = priority.indexOf(f.getName());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(File::getName));
        contentFolders.addAll(ordered);

        List<YamlFile> yamls = new ArrayList<>();
        for (File folder : ordered) {
            List<File> files = new ArrayList<>(FileUtil.listFilesRecursively(new File(folder, "configs"), ".yml", ".yaml"));
            File[] loose = folder.listFiles(f -> f.isFile() && (f.getName().endsWith(".yml") || f.getName().endsWith(".yaml")));
            if (loose != null) files.addAll(Arrays.asList(loose)); // IA also accepts ymls in the folder root
            for (File f : files) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(f);
                } catch (Exception e) {
                    errors++;
                    logger.severe("[" + folder.getName() + "] YAML invalido em " + f.getName() + ": " + e.getMessage());
                    continue;
                }
                String ns = yaml.getString("info.namespace");
                if (ns == null || ns.isBlank()) {
                    // IA requires info.namespace; we fall back to the folder name so old packs still load.
                    ns = folder.getName();
                }
                yamls.add(new YamlFile(f, ns.toLowerCase(Locale.ROOT), yaml));
                folderNamespaces.putIfAbsent(folder, ns.toLowerCase(Locale.ROOT));
            }
        }

        // Dictionaries first, they are needed to resolve display names.
        loadDictionaries(yamls);

        for (YamlFile y : yamls) {
            for (String key : y.yaml.getKeys(false)) {
                if (!SUPPORTED.contains(key)) unsupported.add(key);
            }
            loadItems(y);
            loadEquipments(y);
            loadFontImages(y);
            loadSounds(y);
        }
        // categories/recipes reference items, so they come after every item is known
        for (YamlFile y : yamls) {
            loadCategories(y);
            loadRecipes(y);
        }
    }

    private record YamlFile(File file, String namespace, YamlConfiguration yaml) {
    }

    private void loadDictionaries(List<YamlFile> yamls) {
        Map<String, Map<String, String>> byLang = new HashMap<>();
        for (YamlFile y : yamls) {
            ConfigurationSection d = y.yaml.getConfigurationSection("dictionary");
            if (d == null) continue;
            String lang = y.yaml.getString("info.dictionary-lang", "en");
            Map<String, String> m = byLang.computeIfAbsent(lang, k -> new HashMap<>());
            for (String k : d.getKeys(false)) m.put(k, d.getString(k));
        }
        // fallback order: any language < english < configured language
        byLang.forEach((lang, m) -> {
            if (!lang.equals("en") && !lang.equals(dictionaryLang)) dictionary.putAll(m);
        });
        if (byLang.containsKey("en")) dictionary.putAll(byLang.get("en"));
        if (byLang.containsKey(dictionaryLang)) dictionary.putAll(byLang.get(dictionaryLang));
    }

    public String translate(String text) {
        if (text == null) return null;
        String v = dictionary.get(text);
        return v != null ? v : text;
    }

    private void loadItems(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("items");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            if (s.getBoolean("template", false)) continue; // IA templates are only inherited from
            try {
                CustomItem item = parseItem(y, id.toLowerCase(Locale.ROOT), s);
                if (item != null) items.put(item.namespacedId(), item);
            } catch (Exception e) {
                errors++;
                logger.warning("[" + y.namespace + "] Erro no item '" + id + "' (" + y.file.getName() + "): " + e.getMessage());
            }
        }
    }

    private CustomItem parseItem(YamlFile y, String id, ConfigurationSection s) {
        String ns = y.namespace;
        CustomItem item = new CustomItem(ns, id, s);
        for (String k : UNSUPPORTED_ITEM_KEYS) if (s.contains(k)) unsupported.add("items.*." + k);

        // IA 4.0.13+: top-level "material" + "graphics:"; legacy: "resource.material"
        String mat = s.getString("material", s.getString("resource.material"));
        Material material;
        if (mat == null) {
            // IA legacy custom armors have no material: they were leather armor pieces
            String legacySlot = s.getString("specific_properties.armor.slot");
            material = legacySlot == null ? Material.PAPER : switch (legacySlot.toLowerCase(Locale.ROOT)) {
                case "head" -> Material.LEATHER_HELMET;
                case "chest" -> Material.LEATHER_CHESTPLATE;
                case "legs" -> Material.LEATHER_LEGGINGS;
                case "feet" -> Material.LEATHER_BOOTS;
                default -> Material.PAPER;
            };
        } else {
            material = Material.matchMaterial(mat);
        }
        if (material == null || material.isLegacy()) {
            logger.warning("[" + ns + "] Material invalido '" + mat + "' no item '" + id + "', usando PAPER.");
            material = Material.PAPER;
        }
        item.material = material;

        // IA 4.0.9+ uses "name", older versions "display_name"
        String name = s.getString("name", s.getString("display_name"));
        item.displayName = name == null ? Text.prettify(id) : translate(name);
        for (String line : s.getStringList("lore")) item.lore.add(translate(line));

        // permission_suffix -> ia.user.ia.seeitem.<suffix> (visibility in /ia); "permission" = legacy literal node
        if (s.isString("permission_suffix")) item.permission = "ia.user.ia.seeitem." + s.getString("permission_suffix");
        else item.permission = s.getString("permission");

        ConfigurationSection g = s.getConfigurationSection("graphics");
        if (g != null) {
            parseGraphics(ns, item, g);
        } else {
            item.generate = s.getBoolean("resource.generate", false);
            if (s.isList("resource.textures")) {
                for (String t : s.getStringList("resource.textures")) item.textures.add(normalizeRef(ns, t, ".png"));
            } else if (s.isString("resource.texture")) {
                item.textures.add(normalizeRef(ns, s.getString("resource.texture"), ".png"));
            }
            String modelPath = s.getString("resource.model_path");
            if (modelPath != null) item.modelPath = normalizeRef(ns, modelPath, ".json");
        }
        if (s.isInt("resource.model_id")) item.modelId = s.getInt("resource.model_id");
        item.color = s.getString("resource.color", s.getString("specific_properties.armor.color"));
        if (s.isString("item_model")) item.itemModel = s.getString("item_model");

        // IA 4: durability.max_durability; legacy: durability.max_custom_durability
        item.maxCustomDurability = s.getInt("durability.max_durability", s.getInt("durability.max_custom_durability", 0));
        item.startDurability = s.getInt("durability.durability", item.maxCustomDurability);
        item.disappearWhenBroken = s.getBoolean("durability.disappear_when_broken", true);
        if (s.getBoolean("durability.unbreakable", false)) item.unbreakable = true;

        // 1.21.2+ equipment, plus IA legacy specific_properties.armor
        item.equipmentId = s.getString("equipment.id");
        item.equipmentSlot = s.getString("equipment.slot", s.getString("specific_properties.armor.slot"));
        item.legacyCustomArmor = s.getString("specific_properties.armor.custom_armor");
        if (item.equipmentId == null && item.legacyCustomArmor != null) item.equipmentId = item.legacyCustomArmor;
        if (item.equipmentId != null && !item.equipmentId.contains(":")) item.equipmentId = ns + ":" + item.equipmentId;
        if (item.equipmentSlot == null && item.equipmentId != null) item.equipmentSlot = slotFromMaterial(material);

        ConfigurationSection attrs = s.getConfigurationSection("attribute_modifiers");
        if (attrs != null) {
            for (String slot : attrs.getKeys(false)) {
                ConfigurationSection slotSec = attrs.getConfigurationSection(slot);
                if (slotSec == null) continue;
                Map<String, Double> values = new LinkedHashMap<>();
                for (String attr : slotSec.getKeys(false)) values.put(attr, slotSec.getDouble(attr));
                item.attributeModifiers.put(slot, values);
            }
        }

        item.enchants.addAll(s.getStringList("enchants"));
        item.itemFlags.addAll(s.getStringList("item_flags"));
        if (s.isInt("max_stack_size")) item.maxStackSize = s.getInt("max_stack_size");
        if (s.getBoolean("unbreakable", false)) item.unbreakable = true;
        item.glint = s.getBoolean("glint", s.getBoolean("enchantment_glint_override", false));

        ConfigurationSection b = s.getConfigurationSection("behaviours");
        if (b != null) {
            item.hat = b.getBoolean("hat", false);
            ConfigurationSection f = b.getConfigurationSection("furniture");
            if (f != null) item.furniture = parseFurniture(f);
        }
        return item;
    }

    /**
     * IA modern "graphics:" block. texture/model = single resource; textures/models = map with states
     * (normal, pulling_0.., cast, blocking, arrow, rocket) or per-face/parent layers; parent = explicit parent.
     */
    private void parseGraphics(String ns, CustomItem item, ConfigurationSection g) {
        if (g.isString("model")) {
            item.modelPath = normalizeRef(ns, g.getString("model"), ".json");
            return;
        }
        if (g.isString("texture")) {
            item.generate = true;
            item.textures.add(normalizeRef(ns, g.getString("texture"), ".png"));
            return;
        }
        ConfigurationSection models = g.getConfigurationSection("models");
        if (models != null) {
            for (String state : models.getKeys(false)) {
                item.stateModels.put(state, normalizeRef(ns, models.getString(state), ".json"));
            }
            item.modelPath = item.stateModels.get("normal");
            return;
        }
        ConfigurationSection tex = g.getConfigurationSection("textures");
        if (tex != null) {
            item.generate = true;
            item.parent = g.getString("parent");
            for (String layer : tex.getKeys(false)) {
                item.layerTextures.put(layer, normalizeRef(ns, tex.getString(layer), ".png"));
            }
            String normal = item.layerTextures.get("normal");
            if (normal != null) item.textures.add(normal);
        }
    }

    private static CustomItem.FurnitureSettings parseFurniture(ConfigurationSection f) {
        CustomItem.FurnitureSettings fs = new CustomItem.FurnitureSettings();
        fs.solid = f.getBoolean("solid", true);
        fs.fixedRotation = f.getBoolean("fixed_rotation", false);
        fs.floor = f.getBoolean("placeable_on.floor", true);
        fs.walls = f.getBoolean("placeable_on.walls", false);
        fs.ceiling = f.getBoolean("placeable_on.ceiling", false);
        fs.width = Math.max(1, (int) Math.ceil(f.getDouble("hitbox.width", 1)));
        fs.height = Math.max(1, (int) Math.ceil(f.getDouble("hitbox.height", 1)));
        fs.length = Math.max(1, (int) Math.ceil(f.getDouble("hitbox.length", 1)));
        fs.widthOffset = f.getDouble("hitbox.width_offset", 0);
        fs.heightOffset = f.getDouble("hitbox.height_offset", 0);
        fs.lengthOffset = f.getDouble("hitbox.length_offset", 0);
        fs.lightLevel = f.getInt("light_level", 0);
        fs.placeSound = f.getString("sound.place.name", fs.placeSound);
        fs.breakSound = f.getString("sound.break.name", fs.breakSound);
        fs.entity = f.getString("entity", fs.entity);
        fs.dropWhenMined = f.getBoolean("drop_when_mined", true);
        if (f.isString("permission_suffix.place")) fs.placePermission = "ia.user.furniture.place." + f.getString("permission_suffix.place");
        if (f.isString("permission_suffix.break")) fs.breakPermission = "ia.user.furniture.break." + f.getString("permission_suffix.break");
        return fs;
    }

    private static String slotFromMaterial(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.equals("CARVED_PUMPKIN")) return "HEAD";
        if (n.endsWith("_CHESTPLATE") || n.equals("ELYTRA")) return "CHEST";
        if (n.endsWith("_LEGGINGS")) return "LEGS";
        if (n.endsWith("_BOOTS")) return "FEET";
        return null;
    }

    private void loadEquipments(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("equipments");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                put(new EquipmentDef(y.namespace, id.toLowerCase(Locale.ROOT),
                        refOrNull(y.namespace, s.getString("layer_1")), refOrNull(y.namespace, s.getString("layer_2"))));
            }
        }
        // Legacy IA armors_rendering (renamed legacy_armor_renderings in 4.0.9): rendered with the modern
        // equipment system instead of shaders, so no shader/leather-color tricks are needed.
        for (String legacyKey : List.of("legacy_armor_renderings", "armors_rendering")) {
            ConfigurationSection legacy = y.yaml.getConfigurationSection(legacyKey);
            if (legacy == null) continue;
            for (String id : legacy.getKeys(false)) {
                ConfigurationSection s = legacy.getConfigurationSection(id);
                if (s == null) continue;
                String key = y.namespace + ":" + id.toLowerCase(Locale.ROOT);
                if (equipments.containsKey(key)) continue;
                put(new EquipmentDef(y.namespace, id.toLowerCase(Locale.ROOT),
                        refOrNull(y.namespace, s.getString("layer_1")), refOrNull(y.namespace, s.getString("layer_2"))));
            }
        }
    }

    private void put(EquipmentDef e) {
        equipments.put(e.namespacedId(), e);
    }

    private String refOrNull(String ns, String v) {
        return v == null || v.isBlank() ? null : normalizeRef(ns, v, ".png");
    }

    private void loadFontImages(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("font_images");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            String path = s.getString("path");
            if (path == null) continue;
            FontImage fi = new FontImage(y.namespace, id.toLowerCase(Locale.ROOT), normalizeRef(y.namespace, path, ".png"),
                    s.getInt("scale_ratio", -1), s.getInt("y_position", 8),
                    s.isString("permission") ? "ia.user.image.use." + s.getString("permission") : null,
                    s.getBoolean("show_in_gui", true), s.getBoolean("suggest_in_command", true));
            String symbol = s.getString("symbol");
            if (symbol != null && !symbol.isEmpty()) fi.glyph = symbol.charAt(0);
            fontImages.put(fi.namespacedId(), fi);
        }
    }

    private void loadSounds(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("sounds");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            String path = s.getString("path", name);
            if (path.endsWith(".ogg")) path = path.substring(0, path.length() - 4);
            sounds.add(new SoundDef(y.namespace, name, path, s.getString("settings.subtitle", s.getString("subtitle")),
                    s.getBoolean("settings.stream", s.getBoolean("stream", false)),
                    s.contains("settings.volume") ? s.getDouble("settings.volume") : null,
                    s.contains("settings.pitch") ? s.getDouble("settings.pitch") : null,
                    s.contains("settings.attenuation_distance") ? s.getInt("settings.attenuation_distance") : null));
        }
    }

    private void loadCategories(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("categories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            List<String> list = new ArrayList<>();
            for (String i : s.getStringList("items")) expandCategoryEntry(y.namespace, i, list);
            String icon = s.getString("icon");
            CustomCategory c = new CustomCategory(y.namespace, id.toLowerCase(Locale.ROOT),
                    translate(s.getString("name", Text.prettify(id))),
                    icon == null ? null : qualify(y.namespace, icon), s.getString("permission"), list);
            categories.put(c.namespacedId(), c);
        }
    }

    /** IA category entries: "ns:id", "id", "ns:*" wildcard or a regex like 'iasurvival\:(.*)_helmet'. */
    private void expandCategoryEntry(String ns, String raw, List<String> out) {
        String entry = raw.trim().toLowerCase(Locale.ROOT);
        boolean pattern = entry.contains("*") || entry.contains("(") || entry.contains("\\") || entry.contains("[");
        if (!pattern) {
            out.add(qualify(ns, entry));
            return;
        }
        java.util.regex.Pattern p;
        try {
            p = entry.endsWith(":*") && !entry.contains("(")
                    ? java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(entry.substring(0, entry.length() - 1)) + ".*")
                    : java.util.regex.Pattern.compile(entry);
        } catch (Exception e) {
            logger.warning("[" + ns + "] Padrao de categoria invalido: " + raw);
            return;
        }
        for (String id : items.keySet()) if (p.matcher(id).matches() && !out.contains(id)) out.add(id);
    }

    private void loadRecipes(YamlFile y) {
        ConfigurationSection root = y.yaml.getConfigurationSection("recipes");
        if (root == null) return;
        for (String kind : root.getKeys(false)) {
            ConfigurationSection kindSec = root.getConfigurationSection(kind);
            if (kindSec == null) continue;
            for (String id : kindSec.getKeys(false)) {
                ConfigurationSection s = kindSec.getConfigurationSection(id);
                if (s == null || !s.getBoolean("enabled", true)) continue;
                recipes.add(new RecipeEntry(y.namespace, kind.toLowerCase(Locale.ROOT), id.toLowerCase(Locale.ROOT), s, y.file));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "item/ruby.png" -> "ns:item/ruby"; keeps an explicit namespace; strips extension. */
    public static String normalizeRef(String ns, String raw, String ext) {
        String v = raw.trim().replace('\\', '/');
        if (v.toLowerCase(Locale.ROOT).endsWith(ext)) v = v.substring(0, v.length() - ext.length());
        if (v.startsWith("/")) v = v.substring(1);
        if (!v.contains(":")) v = ns + ":" + v;
        return v.toLowerCase(Locale.ROOT);
    }

    /** Item reference inside a namespace: "ruby" -> "ns:ruby". Vanilla materials stay as is. */
    public String qualify(String ns, String ref) {
        String r = ref.trim().toLowerCase(Locale.ROOT);
        if (r.contains(":")) return r;
        if (items.containsKey(ns + ":" + r)) return ns + ":" + r;
        CustomItem byId = findById(r);
        if (byId != null) return byId.namespacedId();
        return r;
    }

    // ------------------------------------------------------------------ accessors

    public CustomItem getItem(String ref) {
        if (ref == null) return null;
        String r = ref.toLowerCase(Locale.ROOT);
        CustomItem item = items.get(r);
        if (item == null && !r.contains(":")) item = findById(r);
        return item;
    }

    private CustomItem findById(String id) {
        for (CustomItem i : items.values()) if (i.id.equals(id)) return i;
        return null;
    }

    public Collection<CustomItem> items() {
        return items.values();
    }

    public Map<String, CustomCategory> categories() {
        return categories;
    }

    public Map<String, EquipmentDef> equipments() {
        return equipments;
    }

    public Map<String, FontImage> fontImages() {
        return fontImages;
    }

    public List<SoundDef> sounds() {
        return sounds;
    }

    public List<RecipeEntry> recipes() {
        return recipes;
    }

    public List<File> contentFolders() {
        return contentFolders;
    }

    /** Namespace declared by the first config of a content folder (falls back to the folder name). */
    public String namespaceOf(File folder) {
        return folderNamespaces.getOrDefault(folder, folder.getName().toLowerCase(Locale.ROOT));
    }

    public Set<String> unsupportedSections() {
        return unsupported;
    }

    public int errors() {
        return errors;
    }
}
