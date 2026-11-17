package net.byebye.infinityAssets.command;

import net.byebye.infinityAssets.InfinityAssets;
import net.byebye.infinityAssets.content.CustomCategory;
import net.byebye.infinityAssets.content.CustomItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** ItemsAdder-compatible commands: /ia, /iaget, /iagive, /iazip, /iareload, /iatexture, /iatag. */
public final class Commands implements TabExecutor {
    private final InfinityAssets plugin;

    public Commands(InfinityAssets plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "ia" -> ia(sender, args);
            case "iaget" -> iaget(sender, args);
            case "iagive" -> iagive(sender, args);
            case "iazip" -> {
                sender.sendMessage(ChatColor.YELLOW + "Recarregando configs e gerando o resource pack...");
                plugin.reload(true, sender);
            }
            case "iareload" -> {
                plugin.reload(false, sender);
            }
            case "iatexture" -> iatexture(sender, args);
            case "iatag" -> iatag(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void ia(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("ia.user.ia.categories.others")) {
                sender.sendMessage(ChatColor.RED + "Sem permissao.");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            target = sender instanceof Player p ? p : null;
        }
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador nao encontrado.");
            return;
        }
        if (args.length >= 1) {
            if (!sender.hasPermission("ia.user.ia.categories")) {
                sender.sendMessage(ChatColor.RED + "Sem permissao.");
                return;
            }
            CustomCategory c = findCategory(args[0]);
            if (c == null) {
                sender.sendMessage(ChatColor.RED + "Categoria '" + args[0] + "' nao encontrada.");
                return;
            }
            plugin.menus().openCategory(target, c, 0);
        } else {
            plugin.menus().openMain(target);
        }
    }

    private CustomCategory findCategory(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        CustomCategory c = plugin.registry().categories().get(n);
        if (c != null) return c;
        for (CustomCategory x : plugin.registry().categories().values()) if (x.id().equals(n)) return x;
        return null;
    }

    private void iaget(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Use /iagive pelo console.");
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Uso: /iaget <item> [quantidade]");
            return;
        }
        give(sender, p, args[0], args.length >= 2 ? args[1] : "1", false);
    }

    private void iagive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Uso: /iagive <jogador> <item> [quantidade] [silent]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador '" + args[0] + "' nao encontrado.");
            return;
        }
        boolean silent = args.length >= 4 && args[3].equalsIgnoreCase("silent");
        give(sender, target, args[1], args.length >= 3 ? args[2] : "1", silent);
    }

    private void give(CommandSender sender, Player target, String id, String amountStr, boolean silent) {
        CustomItem item = plugin.registry().getItem(id);
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "Item '" + id + "' nao encontrado.");
            return;
        }
        int amount;
        try {
            amount = Math.max(1, Math.min(6400, Integer.parseInt(amountStr)));
        } catch (NumberFormatException e) {
            amount = 1;
        }
        int maxStack = Math.max(1, plugin.factory().create(item, 1).getMaxStackSize());
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, maxStack);
            ItemStack stack = plugin.factory().create(item, n);
            target.getInventory().addItem(stack).values().forEach(x -> target.getWorld().dropItem(target.getLocation(), x));
            left -= n;
        }
        if (!silent) {
            sender.sendMessage(ChatColor.GREEN + "Entregue " + amount + "x " + item.namespacedId() + " para " + target.getName() + ".");
        }
    }

    private void iatexture(CommandSender sender, String[] args) {
        if (args.length >= 1) {
            if (!sender.hasPermission("ia.user.iatexture.all")) {
                sender.sendMessage(ChatColor.RED + "Sem permissao.");
                return;
            }
            if (args[0].equalsIgnoreCase("all")) {
                plugin.packs().sendAll();
                sender.sendMessage(ChatColor.GREEN + "Resource pack reenviado para todos.");
                return;
            }
            Player t = Bukkit.getPlayerExact(args[0]);
            if (t == null) {
                sender.sendMessage(ChatColor.RED + "Jogador nao encontrado.");
                return;
            }
            send(sender, t);
            return;
        }
        if (sender instanceof Player p) send(sender, p);
    }

    private void send(CommandSender sender, Player p) {
        if (!plugin.packs().send(p)) {
            sender.sendMessage(ChatColor.RED + "Nenhum resource pack disponivel (use /iazip e verifique a hospedagem no config.yml).");
        }
    }

    private void iatag(CommandSender sender) {
        if (!(sender instanceof Player p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = plugin.factory().idOf(hand);
        sender.sendMessage(id == null ? ChatColor.GRAY + "O item na mao nao e customizado."
                : ChatColor.GOLD + "Item customizado: " + ChatColor.YELLOW + id
                + (plugin.registry().getItem(id) == null ? ChatColor.RED + " (nao carregado)" : ""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (cmd) {
            case "iaget" -> {
                if (args.length == 1) itemIds(options);
            }
            case "iagive" -> {
                if (args.length == 1) players(options);
                else if (args.length == 2) itemIds(options);
                else if (args.length == 4) options.add("silent");
            }
            case "ia" -> {
                if (args.length == 1) plugin.registry().categories().values().forEach(c -> options.add(c.id()));
                else if (args.length == 2) players(options);
            }
            case "iatexture" -> {
                if (args.length == 1) {
                    options.add("all");
                    players(options);
                }
            }
            default -> {
            }
        }
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).limit(100).toList();
    }

    private void itemIds(List<String> out) {
        for (CustomItem i : plugin.registry().items()) out.add(i.namespacedId());
    }

    private static void players(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }
}
