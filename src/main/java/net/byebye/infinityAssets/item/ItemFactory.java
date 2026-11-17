package net.byebye.infinityAssets.item;

import net.byebye.infinityAssets.content.ContentRegistry;
import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.content.FontImage;
import net.byebye.infinityAssets.util.Text;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns CustomItem definitions into ItemStacks and identifies custom ItemStacks (ours and ItemsAdder's). */
public final class ItemFactory {
    public static final NamespacedKey ID_KEY = new NamespacedKey("infinityassets", "id");
    /** Key used by InfinityAssets 1.0 items. */
    private static final NamespacedKey LEGACY_ID_KEY = new NamespacedKey("infinityassets", "custom_item_id");
    /** ItemsAdder stores {itemsadder:{namespace:"..",id:".."}} in minecraft:custom_data. */
    private static final Pattern IA_TAG = Pattern.compile("itemsadder\\s*:\\s*\\{([^}]*)}");
    private static final Pattern IA_FIELD = Pattern.compile("(namespace|id)\\s*:\\s*\"?([a-z0-9_.\\-/]+)\"?");
    private static final Pattern FONT_TAG = Pattern.compile(":([a-z0-9_]+(?::[a-z0-9_]+)?):");

    private final ContentRegistry registry;
    private final Logger logger;
    private boolean fontImagesInItems = true;
    private boolean attributesAsDisplayed = true;

    public ItemFactory(ContentRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    public void setFontImagesInItems(boolean enabled) {
        this.fontImagesInItems = enabled;
    }

    /** When true, mainhand attackDamage/attackSpeed are the values shown in the tooltip (base 1 / 4 subtracted). */
    public void setAttributesAsDisplayed(boolean enabled) {
        this.attributesAsDisplayed = enabled;
    }

    public ItemStack create(CustomItem item, int amount) {
        ItemStack stack = new ItemStack(item.material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.setItemName(Text.color(fontImages(item.displayName)));
        if (!item.lore.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String l : item.lore) lore.add(Text.color(fontImages(l)));
            meta.setLore(lore);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(ID_KEY, PersistentDataType.STRING, item.namespacedId());

        // model: 1.21.4 item_model component -> assets/<ns>/items/<id>.json
        String modelKey = item.itemModel != null ? item.itemModel : item.namespacedId();
        NamespacedKey model = NamespacedKey.fromString(modelKey);
        if (model != null) meta.setItemModel(model);
        if (item.modelId != null) meta.setCustomModelData(item.modelId);

        if (item.maxCustomDurability > 0) {
            meta.setMaxStackSize(1); // damageable items cannot stack
        } else if (item.maxStackSize != null) {
            meta.setMaxStackSize(Math.max(1, Math.min(99, item.maxStackSize)));
        }

        // custom durability via the vanilla max_damage component: vanilla handles bar, unbreaking, mending, breaking
        if (item.maxCustomDurability > 0 && meta instanceof Damageable d) {
            d.setMaxDamage(item.maxCustomDurability);
            d.setDamage(Math.max(0, item.maxCustomDurability - item.startDurability));
        }
        if (item.unbreakable) meta.setUnbreakable(true);
        if (item.glint) meta.setEnchantmentGlintOverride(true);

        if (item.color != null && meta instanceof LeatherArmorMeta lam) {
            Color c = parseColor(item.color);
            if (c != null) lam.setColor(c);
        }

        if (item.equipmentId != null && item.equipmentSlot != null) {
            EquipmentSlot slot = parseEquipmentSlot(item.equipmentSlot);
            NamespacedKey asset = NamespacedKey.fromString(item.equipmentId);
            if (slot != null && asset != null) {
                EquippableComponent eq = meta.getEquippable();
                eq.setSlot(slot);
                eq.setModel(asset);
                meta.setEquippable(eq);
            }
        }

        applyAttributes(item, meta);

        for (String e : item.enchants) {
            String[] parts = e.split(":");
            int level = 1;
            String key = e;
            if (parts.length >= 2 && parts[parts.length - 1].matches("\\d+")) {
                level = Integer.parseInt(parts[parts.length - 1]);
                key = e.substring(0, e.lastIndexOf(':'));
            }
            Enchantment ench = enchantment(key);
            if (ench != null) meta.addEnchant(ench, level, true);
            else logger.warning("Encantamento desconhecido '" + e + "' no item " + item.namespacedId());
        }

        for (String f : item.itemFlags) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(f.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                logger.warning("item_flag desconhecida '" + f + "' no item " + item.namespacedId());
            }
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private void applyAttributes(CustomItem item, ItemMeta meta) {
        for (Map.Entry<String, Map<String, Double>> slotEntry : item.attributeModifiers.entrySet()) {
            EquipmentSlotGroup group = parseSlotGroup(slotEntry.getKey());
            if (group == null) {
                logger.warning("Slot de atributo desconhecido '" + slotEntry.getKey() + "' no item " + item.namespacedId());
                continue;
            }
            for (Map.Entry<String, Double> attr : slotEntry.getValue().entrySet()) {
                Attribute attribute = attribute(attr.getKey());
                if (attribute == null) {
                    logger.warning("Atributo desconhecido '" + attr.getKey() + "' no item " + item.namespacedId());
                    continue;
                }
                double amount = attr.getValue();
                if (attributesAsDisplayed && group == EquipmentSlotGroup.MAINHAND) {
                    String k = snake(attr.getKey());
                    if (k.equals("attack_damage")) amount -= 1.0;
                    else if (k.equals("attack_speed")) amount -= 4.0;
                }
                NamespacedKey key = new NamespacedKey("infinityassets",
                        (item.namespace + "_" + item.id + "_" + slotEntry.getKey() + "_" + snake(attr.getKey())).toLowerCase(Locale.ROOT));
                meta.addAttributeModifier(attribute, new AttributeModifier(key, amount,
                        AttributeModifier.Operation.ADD_NUMBER, group));
            }
        }
    }

    // ------------------------------------------------------------------ identification

    /** Namespaced id of a custom item stack, recognising our tag and ItemsAdder's own tag. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(ID_KEY, PersistentDataType.STRING);
        if (id == null) id = meta.getPersistentDataContainer().get(LEGACY_ID_KEY, PersistentDataType.STRING);
        if (id != null) return id;
        // Items created by ItemsAdder before migrating keep working.
        try {
            String snbt = meta.getAsString();
            if (snbt == null || !snbt.contains("itemsadder")) return null;
            Matcher m = IA_TAG.matcher(snbt);
            if (!m.find()) return null;
            String ns = null, iid = null;
            Matcher f = IA_FIELD.matcher(m.group(1));
            while (f.find()) {
                if (f.group(1).equals("namespace")) ns = f.group(2);
                else iid = f.group(2);
            }
            return ns != null && iid != null ? ns + ":" + iid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public CustomItem itemOf(ItemStack stack) {
        String id = idOf(stack);
        return id == null ? null : registry.getItem(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Replaces :emoji: / :ns:emoji: with the font_image glyph. */
    public String fontImages(String text) {
        if (text == null || !fontImagesInItems || registry.fontImages().isEmpty() || text.indexOf(':') < 0) return text;
        Matcher m = FONT_TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            FontImage fi = findFontImage(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(fi != null && fi.glyph != 0 ? "§f" + fi.glyph + "§r" : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public FontImage findFontImage(String name) {
        FontImage fi = registry.fontImages().get(name);
        if (fi != null) return fi;
        for (FontImage f : registry.fontImages().values()) if (f.id.equals(name)) return f;
        return null;
    }

    /** "attackDamage" / "attack_damage" / "generic.attack_damage" -> Attribute. */
    static Attribute attribute(String name) {
        String key = snake(name);
        if (key.startsWith("generic_") || key.startsWith("player_")) key = key.substring(key.indexOf('_') + 1);
        if (key.equals("damage")) key = "attack_damage";
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        if (a == null) a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key.replace("_", "")));
        return a;
    }

    static String snake(String name) {
        String s = name.trim().replace('.', '_').replace('-', '_');
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return s.toLowerCase(Locale.ROOT);
    }

    private static Enchantment enchantment(String raw) {
        String k = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = k.contains(":") ? NamespacedKey.fromString(k) : NamespacedKey.minecraft(k);
        Enchantment e = key == null ? null : Registry.ENCHANTMENT.get(key);
        if (e == null) {
            // legacy Bukkit names (DAMAGE_ALL, DURABILITY...)
            @SuppressWarnings("deprecation") Enchantment legacy = Enchantment.getByName(raw.toUpperCase(Locale.ROOT));
            e = legacy;
        }
        return e;
    }

    static EquipmentSlot parseEquipmentSlot(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            case "mainhand", "hand" -> EquipmentSlot.HAND;
            case "offhand", "off_hand" -> EquipmentSlot.OFF_HAND;
            case "body" -> EquipmentSlot.BODY;
            default -> null;
        };
    }

    static EquipmentSlotGroup parseSlotGroup(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> EquipmentSlotGroup.HEAD;
            case "chest", "chestplate" -> EquipmentSlotGroup.CHEST;
            case "legs", "leggings" -> EquipmentSlotGroup.LEGS;
            case "feet", "boots" -> EquipmentSlotGroup.FEET;
            case "mainhand", "hand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand", "off_hand" -> EquipmentSlotGroup.OFFHAND;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "any" -> EquipmentSlotGroup.ANY;
            default -> EquipmentSlotGroup.getByName(s.toLowerCase(Locale.ROOT));
        };
    }

    static Color parseColor(String s) {
        String v = s.trim();
        try {
            if (v.startsWith("#")) return Color.fromRGB(Integer.parseInt(v.substring(1), 16));
            if (v.contains(",")) {
                String[] p = v.split(",");
                return Color.fromRGB(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            }
            return Color.fromRGB(Integer.parseInt(v));
        } catch (Exception e) {
            return null;
        }
    }
}
