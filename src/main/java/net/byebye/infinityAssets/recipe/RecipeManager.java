package net.byebye.infinityAssets.recipe;

import net.byebye.infinityAssets.Settings;
import net.byebye.infinityAssets.content.ContentRegistry;
import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.item.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Registers ItemsAdder "recipes:" (crafting_table, cooking, smithing) as Bukkit recipes.
 * Permission nodes follow IA: ia.user.recipe.&lt;permission or permission_suffix&gt;.
 */
public final class RecipeManager implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private final List<NamespacedKey> registered = new ArrayList<>();
    private final Map<NamespacedKey, String> permissions = new HashMap<>();
    private ContentRegistry registry;
    private ItemFactory factory;
    private boolean unlockOnJoin;

    public RecipeManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void reload(ContentRegistry registry, ItemFactory factory, Settings settings) {
        unregisterAll();
        this.registry = registry;
        this.factory = factory;
        this.unlockOnJoin = settings.unlockRecipes;
        for (ContentRegistry.RecipeEntry e : registry.recipes()) {
            try {
                switch (e.kind()) {
                    case "crafting_table" -> {
                        if (settings.craftingEnabled) crafting(e);
                    }
                    case "cooking" -> {
                        if (settings.cookingEnabled) cooking(e);
                    }
                    case "smithing" -> {
                        if (settings.smithingEnabled) smithing(e);
                    }
                    default -> logger.warning("[" + e.namespace() + "] Tipo de receita nao suportado: recipes." + e.kind());
                }
            } catch (Exception ex) {
                logger.warning("[" + e.namespace() + "] Receita '" + e.id() + "' (" + e.file().getName() + ") invalida: " + ex.getMessage());
            }
        }
        if (unlockOnJoin) for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(registered);
    }

    public int count() {
        return registered.size();
    }

    // ------------------------------------------------------------------ crafting table

    private void crafting(ContentRegistry.RecipeEntry e) {
        ConfigurationSection s = e.section();
        ItemStack result = result(e.namespace(), s.getConfigurationSection("result"));
        ConfigurationSection ing = s.getConfigurationSection("ingredients");
        if (result == null || ing == null) throw new IllegalArgumentException("faltando result/ingredients");
        String perm = permission(s);

        if (s.getBoolean("shapeless", false)) {
            NamespacedKey key = key(e, "");
            ShapelessRecipe r = new ShapelessRecipe(key, result);
            for (String k : ing.getKeys(false)) {
                RecipeChoice c = choice(e.namespace(), ing.get(k));
                if (c != null) r.addIngredient(c);
            }
            add(r, perm);
            return;
        }

        // every key starting with "pattern" is an alternative shape for the same recipe
        int n = 0;
        for (String pk : s.getKeys(false)) {
            if (!pk.startsWith("pattern") || !s.isList(pk)) continue;
            List<String> rows = new ArrayList<>(s.getStringList(pk));
            if (rows.isEmpty()) continue;
            Set<Character> used = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (char ch : rows.get(i).toCharArray()) {
                    // IA: 'X' means empty slot unless it is declared as an ingredient
                    if ((ch == 'X' && !ing.contains("X")) || ch == '_') sb.append(' ');
                    else {
                        sb.append(ch);
                        if (ch != ' ') used.add(ch);
                    }
                }
                rows.set(i, sb.toString());
            }
            ShapedRecipe r = new ShapedRecipe(key(e, n == 0 ? "" : "_" + n), result);
            r.shape(rows.toArray(new String[0]));
            for (char ch : used) {
                RecipeChoice c = choice(e.namespace(), ing.get(String.valueOf(ch)));
                if (c == null) throw new IllegalArgumentException("ingrediente '" + ch + "' invalido ou ausente");
                r.setIngredient(ch, c);
            }
            add(r, perm);
            n++;
        }
        if (n == 0) throw new IllegalArgumentException("sem pattern (use shapeless: true para receitas sem forma)");
    }

    // ------------------------------------------------------------------ cooking

    private void cooking(ContentRegistry.RecipeEntry e) {
        ConfigurationSection s = e.section();
        ItemStack result = result(e.namespace(), s.getConfigurationSection("result"));
        Object rawIng = s.isConfigurationSection("ingredient") ? s.getConfigurationSection("ingredient") : s.get("ingredient");
        RecipeChoice input = choice(e.namespace(), rawIng);
        if (result == null || input == null) throw new IllegalArgumentException("faltando result/ingredient");
        float exp = (float) s.getDouble("exp", 0);
        int time = s.getInt("cook_time", 200);
        List<String> machines = s.getStringList("machines");
        if (machines.isEmpty()) machines = List.of("FURNACE");
        String perm = permission(s);
        for (String m : machines) {
            String machine = m.toUpperCase(Locale.ROOT);
            NamespacedKey key = key(e, "_" + machine.toLowerCase(Locale.ROOT));
            Recipe r = switch (machine) {
                case "FURNACE" -> new FurnaceRecipe(key, result, input, exp, time);
                case "BLAST_FURNACE" -> new BlastingRecipe(key, result, input, exp, time);
                case "SMOKER" -> new SmokingRecipe(key, result, input, exp, time);
                case "CAMPFIRE", "SOUL_CAMPFIRE" -> new CampfireRecipe(key, result, input, exp, time);
                default -> null;
            };
            if (r == null) logger.warning("[" + e.namespace() + "] Maquina desconhecida '" + m + "' na receita " + e.id());
            else add(r, perm);
        }
    }

    // ------------------------------------------------------------------ smithing

    private void smithing(ContentRegistry.RecipeEntry e) {
        ConfigurationSection s = e.section();
        ItemStack result = result(e.namespace(), s.getConfigurationSection("result"));
        RecipeChoice template = choice(e.namespace(), s.get("template"));
        RecipeChoice base = choice(e.namespace(), s.get("base"));
        RecipeChoice addition = choice(e.namespace(), s.get("addition"));
        if (result == null || template == null || base == null || addition == null) {
            throw new IllegalArgumentException("smithing precisa de template, base, addition e result");
        }
        add(new SmithingTransformRecipe(key(e, ""), result, template, base, addition), permission(s));
    }

    // ------------------------------------------------------------------ helpers

    private void add(Recipe r, String perm) {
        NamespacedKey key = ((Keyed) r).getKey();
        Bukkit.removeRecipe(key);
        if (Bukkit.addRecipe(r)) {
            registered.add(key);
            if (perm != null) permissions.put(key, perm);
        }
    }

    private NamespacedKey key(ContentRegistry.RecipeEntry e, String suffix) {
        return new NamespacedKey(plugin, (e.namespace() + "_" + e.kind() + "_" + e.id() + suffix).replaceAll("[^a-z0-9_./-]", "_"));
    }

    private static String permission(ConfigurationSection s) {
        String p = s.getString("permission_suffix", s.getString("permission"));
        return p == null || p.isBlank() ? null : "ia.user.recipe." + p;
    }

    private ItemStack result(String ns, ConfigurationSection r) {
        if (r == null) return null;
        String ref = r.getString("item");
        if (ref == null) return null;
        int amount = r.getInt("amount", 1);
        CustomItem ci = registry.getItem(registry.qualify(ns, ref));
        if (ci != null) return factory.create(ci, amount);
        Material m = Material.matchMaterial(ref);
        if (m == null) throw new IllegalArgumentException("resultado desconhecido '" + ref + "'");
        return new ItemStack(m, amount);
    }

    /** Ingredient: "DIAMOND", "minecraft:diamond", "ns:item", "item" (same namespace) or {item: ...}. */
    private RecipeChoice choice(String ns, Object raw) {
        String ref;
        if (raw instanceof ConfigurationSection sec) ref = sec.getString("item");
        else if (raw instanceof Map<?, ?> map) ref = map.get("item") == null ? null : String.valueOf(map.get("item"));
        else ref = raw == null ? null : String.valueOf(raw);
        if (ref == null || ref.isBlank()) return null;
        String r = ref.trim();
        if (!r.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            CustomItem ci = registry.getItem(registry.qualify(ns, r));
            if (ci != null) return new RecipeChoice.ExactChoice(factory.create(ci, 1));
        }
        Material m = Material.matchMaterial(r);
        if (m != null && m.isItem()) return new RecipeChoice.MaterialChoice(m);
        throw new IllegalArgumentException("ingrediente desconhecido '" + ref + "'");
    }

    private void unregisterAll() {
        for (NamespacedKey k : registered) Bukkit.removeRecipe(k);
        registered.clear();
        permissions.clear();
    }

    public void shutdown() {
        unregisterAll();
    }

    // ------------------------------------------------------------------ permission enforcement

    private boolean denied(HumanEntity who, Recipe recipe) {
        if (!(recipe instanceof Keyed k)) return false;
        String perm = permissions.get(k.getKey());
        return perm != null && !who.hasPermission(perm);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() != null && !e.getViewers().isEmpty() && denied(e.getViewers().get(0), e.getRecipe())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent e) {
        Recipe r = e.getInventory().getRecipe();
        if (r != null && !e.getViewers().isEmpty() && denied(e.getViewers().get(0), r)) e.setResult(null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (unlockOnJoin && !registered.isEmpty()) e.getPlayer().discoverRecipes(registered);
    }
}
