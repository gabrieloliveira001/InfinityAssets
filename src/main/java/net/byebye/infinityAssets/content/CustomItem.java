package net.byebye.infinityAssets.content;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/** One entry of an ItemsAdder-style "items:" section. Plain data; ItemStack creation lives in ItemFactory. */
public final class CustomItem {
    public final String namespace;
    public final String id;
    public final ConfigurationSection section;

    public String displayName;
    public List<String> lore = new ArrayList<>();
    public String permission;
    public Material material;

    // resource
    public boolean generate;
    public List<String> textures = new ArrayList<>();
    public String modelPath;
    public Integer modelId;
    public String color;
    /** graphics.parent (modern block/item parents like block/cross). */
    public String parent;
    /** graphics.models: state -> model ref (normal, pulling_0, cast, blocking, arrow, rocket...). */
    public final Map<String, String> stateModels = new LinkedHashMap<>();
    /** graphics.textures: state or layer -> texture ref. */
    public final Map<String, String> layerTextures = new LinkedHashMap<>();
    /** Expert "item_model" override (ns:id). */
    public String itemModel;

    public int maxCustomDurability;
    public int startDurability;
    public boolean disappearWhenBroken = true;
    public String equipmentId;
    public String equipmentSlot;
    public String legacyCustomArmor;
    /** slot -> attribute -> amount */
    public final Map<String, Map<String, Double>> attributeModifiers = new LinkedHashMap<>();
    public final List<String> enchants = new ArrayList<>();
    public final List<String> itemFlags = new ArrayList<>();
    public Integer maxStackSize;
    public boolean unbreakable;
    public boolean glint;

    // behaviours
    public boolean hat;
    public FurnitureSettings furniture;

    /** Resolved by the pack generator: namespaced model used in the item definition. */
    public String resolvedModel;

    public CustomItem(String namespace, String id, ConfigurationSection section) {
        this.namespace = namespace;
        this.id = id;
        this.section = section;
    }

    public String namespacedId() {
        return namespace + ":" + id;
    }

    public static final class FurnitureSettings {
        public boolean solid = true;
        public boolean fixedRotation;
        public boolean floor = true, walls, ceiling;
        public int width = 1, height = 1, length = 1;
        public double heightOffset, widthOffset, lengthOffset;
        public int lightLevel;
        public String placeSound = "block.wood.place";
        public String breakSound = "block.wood.break";
        public String entity = "armor_stand";
        public boolean dropWhenMined = true;
        public String placePermission;
        public String breakPermission;
    }
}
