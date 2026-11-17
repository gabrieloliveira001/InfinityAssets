package net.byebye.infinityAssets.menu;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class MenuHolder implements InventoryHolder {
    /** MAIN = category picker, LIST = items of one category (categoryId) or of every category (null). */
    public enum Type {MAIN, LIST}

    public final Type type;
    public final String categoryId;
    public final int page;
    public final int pages;
    /** slot -> category id (MAIN) or item id (LIST) */
    public final Map<Integer, String> slots = new HashMap<>();

    public MenuHolder(Type type, String categoryId, int page, int pages) {
        this.type = type;
        this.categoryId = categoryId;
        this.page = page;
        this.pages = pages;
    }

    @Override
    public Inventory getInventory() {
        return Bukkit.createInventory(this, 9);
    }
}
