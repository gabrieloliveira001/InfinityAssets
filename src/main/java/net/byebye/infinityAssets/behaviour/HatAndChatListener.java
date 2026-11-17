package net.byebye.infinityAssets.behaviour;

import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.content.FontImage;
import net.byebye.infinityAssets.item.ItemFactory;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** behaviours.hat (wear any custom item on the head) and font_images :emoji: replacement in chat. */
public final class HatAndChatListener implements Listener {
    private static final Pattern TAG = Pattern.compile(":([a-z0-9_]+(?::[a-z0-9_]+)?):");
    private final ItemFactory factory;
    private final boolean chatEmojis;

    public HatAndChatListener(ItemFactory factory, boolean chatEmojis) {
        this.factory = factory;
        this.chatEmojis = chatEmojis;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        CustomItem item = factory.itemOf(hand);
        if (item == null || !item.hat || item.furniture != null) return;
        Player p = e.getPlayer();
        ItemStack helmet = p.getInventory().getHelmet();
        if (helmet != null && !helmet.getType().isAir()) return;
        e.setCancelled(true);
        ItemStack worn = hand.clone();
        worn.setAmount(1);
        p.getInventory().setHelmet(worn);
        hand.setAmount(hand.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHelmetSlot(InventoryClickEvent e) {
        if (e.getSlotType() != InventoryType.SlotType.ARMOR || e.getRawSlot() != 5) return; // 5 = helmet in player inventory view
        ItemStack cursor = e.getCursor();
        CustomItem item = factory.itemOf(cursor);
        if (item == null || !item.hat || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        ItemStack current = e.getCurrentItem();
        ItemStack worn = cursor.clone();
        worn.setAmount(1);
        cursor.setAmount(cursor.getAmount() - 1);
        p.getInventory().setHelmet(worn);
        if (current != null && !current.getType().isAir()) {
            if (cursor.getAmount() <= 0) p.setItemOnCursor(current);
            else p.getInventory().addItem(current).values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
        } else {
            p.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
        }
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!chatEmojis || e.getMessage().indexOf(':') < 0) return;
        Player p = e.getPlayer();
        Matcher m = TAG.matcher(e.getMessage());
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            FontImage fi = factory.findFontImage(m.group(1));
            boolean ok = fi != null && fi.glyph != 0 && (fi.permission == null || p.hasPermission(fi.permission))
                    && p.hasPermission("ia.user.image.chat");
            m.appendReplacement(sb, Matcher.quoteReplacement(ok ? "§f" + fi.glyph + "§r" : m.group()));
            changed |= ok;
        }
        m.appendTail(sb);
        if (changed) e.setMessage(sb.toString());
    }
}
