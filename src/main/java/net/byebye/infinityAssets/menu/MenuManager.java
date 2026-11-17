package net.byebye.infinityAssets.menu;

import net.byebye.infinityAssets.InfinityAssets;
import net.byebye.infinityAssets.content.CustomCategory;
import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The /ia menu: one category picker (with an ALL button) and a paged item list.
 * With the resource pack loaded it uses the textured layout from {@link MenuTheme}; players
 * without the pack get the same slot layout with plain vanilla buttons.
 */
public final class MenuManager implements Listener {
    // category picker
    private static final int[] CATEGORY_SLOTS = {18, 19, 20, 21, 22, 23, 24, 27, 28, 29, 30, 31, 32, 33};
    private static final int CLOSE = 0, INFO = 8, ALL = 26, HOME_NEXT = 35;
    // item list
    private static final int GRID_FIRST = 9, PAGE_SIZE = 36, PREV = 45, BACK = 49, NEXT = 53;

    private final InfinityAssets plugin;
    /** Players whose client refused or failed the pack: they get the plain menu. */
    private final Set<UUID> noPack = new HashSet<>();

    public MenuManager(InfinityAssets plugin) {
        this.plugin = plugin;
    }

    private boolean styled(Player p) {
        return plugin.settings() != null && plugin.settings().customMenu && !noPack.contains(p.getUniqueId());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        switch (e.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> noPack.add(e.getPlayer().getUniqueId());
            case SUCCESSFULLY_LOADED -> noPack.remove(e.getPlayer().getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        noPack.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ data
    private List<CustomCategory> visibleCategories(Player p) {
        List<CustomCategory> out = new ArrayList<>();
        for (CustomCategory c : plugin.registry().categories().values()) {
            if (c.permission() == null || c.permission().isBlank() || p.hasPermission(c.permission())
                    || p.hasPermission("ia.menu.seecategory.*")) out.add(c);
        }
        return out;
    }

    private List<CustomItem> visibleItems(Player p, CustomCategory c) {
        List<CustomItem> items = new ArrayList<>();
        if (c == null) {
            for (CustomItem i : plugin.registry().items()) if (canSee(p, i)) items.add(i);
        } else {
            for (String id : c.items()) {
                CustomItem i = plugin.registry().getItem(id);
                if (i != null && canSee(p, i)) items.add(i);
            }
        }
        return items;
    }

    private static boolean canSee(Player p, CustomItem i) {
        return i.permission == null || p.hasPermission(i.permission) || p.hasPermission("ia.user.ia.seeitem.*");
    }

    private static boolean canTake(Player p) {
        return p.hasPermission("ia.admin.iaget") || p.hasPermission("ia.user.ia.getitem");
    }

    // ------------------------------------------------------------------ screens
    public void openMain(Player p) {
        openMain(p, 0);
    }

    public void openMain(Player p, int page) {
        List<CustomCategory> cats = visibleCategories(p);
        int perPage = cats.size() <= 7 ? 7 : CATEGORY_SLOTS.length;
        int pages = Math.max(1, (cats.size() + perPage - 1) / perPage);
        page = Math.floorMod(page, pages);
        boolean styled = styled(p);
        MenuTheme.Background bg = cats.size() <= 7 ? MenuTheme.Background.HOME_1
                : pages > 1 ? MenuTheme.Background.HOME_2P : MenuTheme.Background.HOME_2;
        String title = styled ? MenuTheme.homeTitle(bg) : ChatColor.DARK_GRAY + "InfinityAssets";

        MenuHolder holder = new MenuHolder(MenuHolder.Type.MAIN, null, page, pages);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= cats.size()) break;
            CustomCategory c = cats.get(index);
            ItemStack icon = icon(c);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setItemName(Text.color(c.name()));
                meta.setLore(List.of(ChatColor.GRAY + count(visibleItems(p, c).size()), "",
                        ChatColor.YELLOW + "Click to open"));
                meta.addItemFlags(ItemFlag.values());
                icon.setItemMeta(meta);
            }
            holder.slots.put(CATEGORY_SLOTS[i], c.namespacedId());
            inv.setItem(CATEGORY_SLOTS[i], icon);
        }
        int total = visibleItems(p, null).size();
        inv.setItem(CLOSE, button(styled, Material.BARRIER, "&cClose"));
        inv.setItem(INFO, button(styled, Material.BOOK, "&bInfinityAssets",
                "&7" + cats.size() + " categories, " + count(total), "",
                "&7Pick a category, or ALL", "&7to see every item."));
        inv.setItem(ALL, button(styled, Material.CHEST, "&6All items", "&7" + count(total), "", "&eClick to open"));
        if (pages > 1) {
            inv.setItem(HOME_NEXT, button(styled, Material.ARROW, "&eMore categories",
                    "&7Page " + (page + 1) + "/" + pages));
        }
        p.openInventory(inv);
    }

    /** Items of one category; null = every item. */
    public void openCategory(Player p, CustomCategory c, int page) {
        List<CustomItem> items = visibleItems(p, c);
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        boolean styled = styled(p);
        String name = c == null ? "All items" : ChatColor.stripColor(Text.color(c.name()));
        String title = styled ? MenuTheme.itemsTitle(c == null ? "ALL ITEMS" : name, page + 1, pages, items.size())
                : ChatColor.DARK_GRAY + name + " (" + (page + 1) + "/" + pages + ")";

        MenuHolder holder = new MenuHolder(MenuHolder.Type.LIST, c == null ? null : c.namespacedId(), page, pages);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        boolean take = canTake(p);
        for (int i = page * PAGE_SIZE, slot = GRID_FIRST; i < items.size() && slot < GRID_FIRST + PAGE_SIZE; i++, slot++) {
            CustomItem item = items.get(i);
            holder.slots.put(slot, item.namespacedId());
            inv.setItem(slot, display(item, take));
        }
        inv.setItem(CLOSE, button(styled, Material.BARRIER, "&cClose"));
        inv.setItem(INFO, button(styled, Material.BOOK, "&b" + name, "&7" + count(items.size()),
                "&7Page " + (page + 1) + "/" + pages));
        if (styled || page > 0) inv.setItem(PREV, button(styled, Material.ARROW, "&ePrevious page"));
        inv.setItem(BACK, button(styled, Material.SPECTRAL_ARROW, "&6Back to categories"));
        if (styled || page < pages - 1) inv.setItem(NEXT, button(styled, Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    private ItemStack display(CustomItem item, boolean take) {
        ItemStack stack = plugin.factory().create(item, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + item.namespacedId());
        if (take) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Left click: " + ChatColor.WHITE + "get 1");
            lore.add(ChatColor.GRAY + "Shift click: " + ChatColor.WHITE + "get a stack");
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String count(int n) {
        return n + (n == 1 ? " item" : " items");
    }

    private ItemStack icon(CustomCategory c) {
        CustomItem i = c.icon() == null ? null : plugin.registry().getItem(c.icon());
        if (i != null) return plugin.factory().create(i, 1);
        Material m = c.icon() == null ? null : Material.matchMaterial(c.icon());
        return new ItemStack(m != null && m.isItem() ? m : Material.CHEST);
    }

    /** Styled: an invisible item over art baked in the texture. Plain: a normal vanilla item. */
    private static ItemStack button(boolean styled, Material plain, String name, String... lore) {
        ItemStack s = new ItemStack(styled ? Material.PAPER : plain);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            if (styled) meta.setItemModel(NamespacedKey.fromString(MenuTheme.BLANK_MODEL));
            meta.setItemName(Text.color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String line : lore) l.add(Text.color(line));
                meta.setLore(l);
            }
            meta.addItemFlags(ItemFlag.values());
            s.setItemMeta(meta);
        }
        return s;
    }

    // ------------------------------------------------------------------ clicks
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (slot == CLOSE) {
            click(p);
            p.closeInventory();
            return;
        }
        if (holder.type == MenuHolder.Type.MAIN) {
            if (slot == ALL) {
                click(p);
                openCategory(p, null, 0);
            } else if (slot == HOME_NEXT && holder.pages > 1) {
                click(p);
                openMain(p, holder.page + 1);
            } else {
                String id = holder.slots.get(slot);
                CustomCategory c = id == null ? null : plugin.registry().categories().get(id);
                if (c != null) {
                    click(p);
                    openCategory(p, c, 0);
                }
            }
            return;
        }
        CustomCategory c = holder.categoryId == null ? null : plugin.registry().categories().get(holder.categoryId);
        if (holder.categoryId != null && c == null) {
            p.closeInventory();
            return;
        }
        switch (slot) {
            case PREV -> {
                if (holder.page > 0) {
                    click(p);
                    openCategory(p, c, holder.page - 1);
                }
            }
            case NEXT -> {
                if (holder.page < holder.pages - 1) {
                    click(p);
                    openCategory(p, c, holder.page + 1);
                }
            }
            case BACK -> {
                click(p);
                openMain(p);
            }
            default -> {
                String id = holder.slots.get(slot);
                CustomItem item = id == null ? null : plugin.registry().getItem(id);
                if (item == null) return;
                if (!canTake(p)) {
                    p.sendMessage(ChatColor.RED + "You don't have permission to take items from the menu.");
                    return;
                }
                int amount = e.isShiftClick() ? item.material.getMaxStackSize() : 1;
                p.getInventory().addItem(plugin.factory().create(item, amount))
                        .values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            }
        }
    }

    private static void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }
}
