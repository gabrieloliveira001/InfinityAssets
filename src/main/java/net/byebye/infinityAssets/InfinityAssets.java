package net.byebye.infinityAssets;

import net.byebye.infinityAssets.behaviour.FurnitureManager;
import net.byebye.infinityAssets.behaviour.HatAndChatListener;
import net.byebye.infinityAssets.command.Commands;
import net.byebye.infinityAssets.content.ContentRegistry;
import net.byebye.infinityAssets.item.ItemFactory;
import net.byebye.infinityAssets.menu.MenuManager;
import net.byebye.infinityAssets.pack.PackGenerator;
import net.byebye.infinityAssets.pack.PackService;
import net.byebye.infinityAssets.recipe.RecipeManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Standalone custom-content plugin that reads ItemsAdder-format configs (contents/&lt;folder&gt;/configs/*.yml)
 * and builds its own resource pack. Needs neither ItemsAdder nor Oraxen.
 */
public final class InfinityAssets extends JavaPlugin {
    private Settings settings;
    private ContentRegistry registry;
    private ItemFactory factory;
    private RecipeManager recipes;
    private PackService packs;
    private MenuManager menus;
    private final List<org.bukkit.event.Listener> contentListeners = new ArrayList<>();
    private volatile boolean building;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File contents = new File(getDataFolder(), "contents");
        if (!contents.exists() || contents.list() == null || contents.list().length == 0) extractDefaultContents();

        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
            getLogger().warning("ItemsAdder tambem esta instalado: os comandos /ia* e o resource pack vao conflitar. Use apenas um dos dois.");
        }

        packs = new PackService(this);
        recipes = new RecipeManager(this);
        menus = new MenuManager(this);
        getServer().getPluginManager().registerEvents(packs, this);
        getServer().getPluginManager().registerEvents(recipes, this);
        getServer().getPluginManager().registerEvents(menus, this);

        Commands cmd = new Commands(this);
        for (String name : List.of("ia", "iaget", "iagive", "iazip", "iareload", "iatexture", "iatag")) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(cmd);
                pc.setTabCompleter(cmd);
            }
        }

        reload(true, null);
    }

    @Override
    public void onDisable() {
        if (packs != null) packs.stop();
        if (recipes != null) recipes.shutdown();
    }

    /** /iareload (buildPack=false) and /iazip (buildPack=true). */
    public void reload(boolean buildPack, CommandSender sender) {
        if (building) {
            if (sender != null) sender.sendMessage(ChatColor.RED + "O resource pack ja esta sendo gerado, aguarde.");
            return;
        }
        long start = System.currentTimeMillis();
        reloadConfig();
        settings = new Settings(getConfig());

        ContentRegistry newRegistry = new ContentRegistry(getLogger(), settings.dictionaryLang);
        newRegistry.load(new File(getDataFolder(), "contents"), settings.foldersPriority);
        PackGenerator.assignGlyphs(getDataFolder(), newRegistry);
        registry = newRegistry;
        factory = new ItemFactory(registry, getLogger());
        factory.setFontImagesInItems(settings.fontImagesInItems);
        factory.setAttributesAsDisplayed(settings.attributesAsDisplayedValues);
        recipes.reload(registry, factory, settings);
        if (settings.usedLegacyKeys) {
            getLogger().warning("config.yml usa as chaves antigas (resource-pack.host/port/force). Elas ainda funcionam, mas"
                    + " mude para resource-pack.hosting.self-host.server-ip / pack-port (formato do ItemsAdder).");
        }
        if (settings.simpleSelfHostFallback) {
            getLogger().warning("simple_self_host do ItemsAdder nao e suportado; o pack sera servido em http://"
                    + settings.selfHostIp + ":" + settings.selfHostPort + ". Configure resource-pack.hosting.self-host.");
        }

        for (org.bukkit.event.Listener l : contentListeners) HandlerList.unregisterAll(l);
        contentListeners.clear();
        contentListeners.add(new FurnitureManager(factory, settings.maxFurniturePerChunk));
        contentListeners.add(new HatAndChatListener(factory, settings.fontImagesInChat));
        for (org.bukkit.event.Listener l : contentListeners) getServer().getPluginManager().registerEvents(l, this);

        String summary = registry.items().size() + " itens, " + registry.categories().size() + " categorias, "
                + recipes.count() + " receitas, " + registry.fontImages().size() + " font_images, "
                + registry.equipments().size() + " equipamentos";
        getLogger().info("Carregado: " + summary + " (" + (System.currentTimeMillis() - start) + "ms)");
        if (!registry.unsupportedSections().isEmpty()) {
            getLogger().warning("Secoes do ItemsAdder ainda nao suportadas (ignoradas): " + String.join(", ", registry.unsupportedSections()));
        }
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Configs recarregadas: " + summary + "."
                    + (registry.errors() > 0 ? ChatColor.RED + " " + registry.errors() + " erro(s), veja o console." : ""));
        }

        if (!buildPack) {
            packs.refresh(settings);
            return;
        }
        building = true;
        ContentRegistry snapshot = registry;
        PackGenerator.Options options = new PackGenerator.Options(settings.ignoredExtensions, otherPluginFolders(),
                "InfinityAssets resource pack", settings.customMenu, settings.hideInventoryLabel);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PackGenerator.Result result = null;
            Exception error = null;
            try {
                result = new PackGenerator(getDataFolder(), getLogger()).generate(snapshot, options);
            } catch (Exception e) {
                error = e;
            }
            final PackGenerator.Result r = result;
            final Exception err = error;
            Bukkit.getScheduler().runTask(this, () -> {
                building = false;
                if (err != null) {
                    getLogger().severe("Falha ao gerar o resource pack: " + err);
                    err.printStackTrace();
                    if (sender != null) sender.sendMessage(ChatColor.RED + "Falha ao gerar o resource pack, veja o console.");
                    return;
                }
                packs.refresh(settings);
                String msg = "Resource pack gerado: " + r.zip().getName() + " (" + (r.zip().length() / 1024) + " KB)"
                        + (r.problems() > 0 ? ", " + r.problems() + " aviso(s) no console" : "");
                getLogger().info(msg);
                if (sender != null) sender.sendMessage(ChatColor.GREEN + msg);
                packs.sendAll();
            });
        });
    }

    private List<File> otherPluginFolders() {
        List<File> out = new ArrayList<>();
        File pluginsDir = getDataFolder().getParentFile();
        for (String rel : settings.mergeOtherPluginsFolders) out.add(new File(pluginsDir, rel));
        return out;
    }

    private void extractDefaultContents() {
        try {
            URL dirURL = getClass().getClassLoader().getResource("contents");
            if (dirURL == null || !dirURL.getProtocol().equals("jar")) return;
            JarFile jar = ((JarURLConnection) dirURL.openConnection()).getJarFile();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("contents/") || entry.isDirectory()) continue;
                File dest = new File(getDataFolder(), entry.getName());
                dest.getParentFile().mkdirs();
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            getLogger().severe("Nao foi possivel extrair os conteudos padrao: " + e.getMessage());
        }
    }

    public Settings settings() {
        return settings;
    }

    public ContentRegistry registry() {
        return registry;
    }

    public ItemFactory factory() {
        return factory;
    }

    public PackService packs() {
        return packs;
    }

    public MenuManager menus() {
        return menus;
    }
}
