package net.byebye.infinityAssets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** Reads config.yml using ItemsAdder key paths (both the current "hosting:" layout and the older flat one). */
public final class Settings {
    public enum Hosting {NO_HOST, SELF_HOST, EXTERNAL_HOST}

    public final Hosting hosting;
    public final String selfHostIp;
    public final int selfHostPort;
    public final boolean appendPort;
    public final boolean blockNonGameRequests;
    public final String externalUrl;
    public final boolean applyOnJoin;
    public final int delayTicks;
    public final boolean kickOnDecline;
    public final boolean kickOnFail;
    public final String promptMessage;
    public final String packUuid;
    public final List<String> ignoredExtensions;
    public final List<String> foldersPriority;
    public final List<String> mergeOtherPluginsFolders;
    public final String dictionaryLang;
    public final boolean fontImagesInChat;
    public final boolean fontImagesInItems;
    public final boolean craftingEnabled;
    public final boolean cookingEnabled;
    public final boolean smithingEnabled;
    public final boolean unlockRecipes;
    public final int maxFurniturePerChunk;
    public final boolean attributesAsDisplayedValues;
    /** Textured /ia menu (needs the resource pack); off = plain chest menu. */
    public final boolean customMenu;
    public final boolean hideInventoryLabel;
    /** True when only IA's simple_self_host is enabled (served on the MC port by IA; here on pack-port). */
    public final boolean simpleSelfHostFallback;
    public final boolean usedLegacyKeys;

    public Settings(FileConfiguration c) {
        ConfigurationSection rp = c.getConfigurationSection("resource-pack");
        if (rp == null) rp = c.createSection("resource-pack");
        ConfigurationSection hostingSec = rp.getConfigurationSection("hosting");
        if (hostingSec == null) hostingSec = rp; // old IA layout: self-host/external-host directly under resource-pack

        if (hostingSec.getBoolean("external-host.enabled", false)) hosting = Hosting.EXTERNAL_HOST;
        else if (hostingSec.getBoolean("self-host.enabled", false) || hostingSec.getBoolean("simple_self_host.enabled", false)) hosting = Hosting.SELF_HOST;
        else if (hostingSec.getBoolean("no-host.enabled", false)) hosting = Hosting.NO_HOST;
        else hosting = Hosting.SELF_HOST;

        simpleSelfHostFallback = !hostingSec.getBoolean("self-host.enabled", false)
                && hostingSec.getBoolean("simple_self_host.enabled", false);
        // InfinityAssets 1.0 keys (resource-pack.host / port / force), kept so old configs keep working
        usedLegacyKeys = rp.contains("host") || rp.contains("port");
        String ip = hostingSec.getString("self-host.server-ip");
        if (usedLegacyKeys && !hostingSec.contains("self-host.server-ip")) ip = rp.getString("host");
        if (ip == null || ip.isBlank() || ip.equalsIgnoreCase("auto")) {
            ip = hostingSec.getString("simple_self_host.server_address", "127.0.0.1");
        }
        selfHostIp = ip.equalsIgnoreCase("auto") ? "127.0.0.1" : ip;
        selfHostPort = hostingSec.getInt("self-host.pack-port", rp.getInt("port", 8163));
        appendPort = hostingSec.getBoolean("self-host.append-port", true);
        blockNonGameRequests = hostingSec.getBoolean("self-host.protection.block_non_game_requests",
                hostingSec.getBoolean("simple_self_host.optimization.block_non_game_requests", false));
        externalUrl = hostingSec.getString("external-host.url", "");

        applyOnJoin = rp.getBoolean("auto_apply.enabled", rp.getBoolean("apply-on-join", true));
        delayTicks = Math.max(0, rp.getInt("auto_apply.delay_ticks", rp.getInt("delay-ticks", 1)));
        kickOnDecline = rp.getBoolean("kick-player-on-decline", rp.getBoolean("force", false));
        kickOnFail = rp.getBoolean("kick-player-on-fail", false);
        promptMessage = rp.getString("prompt-message", "");
        packUuid = rp.getString("uuid", "d69238f2-b7ce-30b0-8262-17cd9490f29d");

        ignoredExtensions = rp.getStringList("zip.ignore-files-ext");
        foldersPriority = rp.getStringList("zip.contents-folders-priority");
        mergeOtherPluginsFolders = rp.getStringList("zip.merge_other_plugins_resourcepacks_folders");

        dictionaryLang = c.getString("config_files.dictionaries-lang", "en");
        fontImagesInChat = c.getBoolean("font_images.chat.enabled", true);
        fontImagesInItems = c.getBoolean("font_images.customitem_name_and_lore.enabled", true);
        craftingEnabled = c.getBoolean("recipes.crafting.enabled", true);
        cookingEnabled = c.getBoolean("recipes.cooking.enabled", true);
        smithingEnabled = c.getBoolean("recipes.smithing.enabled", true);
        unlockRecipes = c.getBoolean("recipes.custom-recipes.unlock.automatically", true);
        maxFurniturePerChunk = c.getInt("entities.max_furniture_per_chunk", 20);
        attributesAsDisplayedValues = c.getBoolean("items.attribute_modifiers_as_displayed_values", true);
        customMenu = c.getBoolean("menu.custom-gui", true);
        hideInventoryLabel = c.getBoolean("menu.hide-inventory-label", true);
    }

    /** Public URL players download the pack from, or null when no hosting is configured. */
    public String packUrl() {
        return switch (hosting) {
            case NO_HOST -> null;
            case EXTERNAL_HOST -> externalUrl == null || externalUrl.isBlank() ? null : externalUrl;
            case SELF_HOST -> "http://" + selfHostIp + (appendPort ? ":" + selfHostPort : "") + "/generated.zip";
        };
    }
}
