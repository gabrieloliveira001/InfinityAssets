package net.byebye.infinityAssets.pack;

import net.byebye.infinityAssets.Settings;
import net.byebye.infinityAssets.util.FileUtil;
import net.byebye.infinityAssets.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.UUID;

/** Hosts (self-host / external-host) and sends output/generated.zip, mirroring ItemsAdder's resource-pack options. */
public final class PackService implements Listener {
    private final Plugin plugin;
    private final PackServer server;
    private Settings settings;
    private byte[] hash;
    private String url;

    public PackService(Plugin plugin) {
        this.plugin = plugin;
        this.server = new PackServer(plugin.getLogger());
    }

    public File packFile() {
        return new File(plugin.getDataFolder(), "output/generated.zip");
    }

    /** (Re)reads the zip hash and (re)starts hosting. Call after every pack build. */
    public void refresh(Settings settings) {
        this.settings = settings;
        File zip = packFile();
        hash = null;
        url = null;
        if (!zip.exists()) return;
        try {
            hash = FileUtil.sha1(zip);
        } catch (Exception e) {
            plugin.getLogger().severe("Falha ao calcular SHA-1 do pack: " + e.getMessage());
            return;
        }
        if (settings.hosting == Settings.Hosting.SELF_HOST) {
            if (!server.isRunning() || runningPort != settings.selfHostPort) {
                runningPort = settings.selfHostPort;
                if (server.start(settings.selfHostPort, zip, settings.blockNonGameRequests)) {
                    plugin.getLogger().info("Resource pack hospedado em " + settings.packUrl());
                }
            }
        } else {
            server.stop();
            runningPort = -1;
        }
        url = settings.packUrl();
        if (settings.hosting == Settings.Hosting.EXTERNAL_HOST && url == null) {
            plugin.getLogger().warning("external-host habilitado mas resource-pack.hosting.external-host.url esta vazio.");
        }
    }

    private int runningPort = -1;

    public String hashHex() {
        return hash == null ? null : FileUtil.toHex(hash);
    }

    public void stop() {
        server.stop();
    }

    public boolean send(Player player) {
        if (url == null || hash == null) return false;
        UUID id;
        try {
            id = UUID.fromString(settings.packUuid);
        } catch (IllegalArgumentException e) {
            id = UUID.nameUUIDFromBytes(url.getBytes());
        }
        String prompt = settings.promptMessage == null || settings.promptMessage.isBlank() ? null : Text.color(settings.promptMessage);
        // cache-buster so clients re-download when the content changes
        String finalUrl = url + (url.contains("?") ? "&" : "?") + "v=" + hashHex().substring(0, 10);
        player.setResourcePack(id, finalUrl, hash, prompt, settings.kickOnDecline);
        return true;
    }

    public void sendAll() {
        for (Player p : Bukkit.getOnlinePlayers()) send(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (settings == null || !settings.applyOnJoin) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) send(p);
        }, Math.max(1, settings.delayTicks));
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (settings == null || e.getPlayer().hasPermission("ia.resourcepack.bypasskick")) return;
        switch (e.getStatus()) {
            case DECLINED -> {
                if (settings.kickOnDecline) e.getPlayer().kickPlayer("Voce precisa aceitar o resource pack para jogar neste servidor.");
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                if (settings.kickOnFail) e.getPlayer().kickPlayer("Falha ao baixar o resource pack. Tente entrar novamente.");
            }
            default -> {
            }
        }
    }
}
