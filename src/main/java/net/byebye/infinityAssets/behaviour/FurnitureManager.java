package net.byebye.infinityAssets.behaviour;

import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.item.ItemFactory;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * ItemsAdder "behaviours.furniture": placed as an ItemDisplay; solid furniture fills its hitbox with barriers,
 * non-solid furniture gets an Interaction entity so it can still be hit/broken.
 */
public final class FurnitureManager implements Listener {
    private static final NamespacedKey FURNITURE = new NamespacedKey("infinityassets", "furniture");
    private static final NamespacedKey BLOCKS = new NamespacedKey("infinityassets", "furniture_blocks");
    private static final NamespacedKey LINK = new NamespacedKey("infinityassets", "furniture_link");
    /** InfinityAssets 1.0 furniture: ItemDisplay tagged with the barrier location "world,x,y,z". */
    private static final NamespacedKey LEGACY_LOC = new NamespacedKey("infinityassets", "furniture_loc");

    private final ItemFactory factory;
    private final int maxPerChunk;

    public FurnitureManager(ItemFactory factory, int maxPerChunk) {
        this.factory = factory;
        this.maxPerChunk = maxPerChunk;
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = e.getItem();
        CustomItem item = factory.itemOf(hand);
        if (item == null || item.furniture == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        CustomItem.FurnitureSettings f = item.furniture;
        if (f.placePermission != null && !p.hasPermission(f.placePermission)) {
            p.sendMessage(ChatColor.RED + "Voce nao tem permissao para colocar este movel.");
            return;
        }
        Block clicked = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (clicked == null) return;
        boolean allowed = switch (face) {
            case UP -> f.floor;
            case DOWN -> f.ceiling;
            default -> f.walls;
        };
        if (!allowed) return;
        Block origin = clicked.getType().isSolid() || !replaceable(clicked) ? clicked.getRelative(face) : clicked;

        if (countInChunk(origin.getChunk()) >= maxPerChunk && maxPerChunk > 0) {
            p.sendMessage(ChatColor.RED + "Limite de moveis neste chunk atingido.");
            return;
        }

        float yaw;
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            float step = f.fixedRotation ? 90f : 45f;
            yaw = Math.round((p.getLocation().getYaw() + 180f) / step) * step;
        } else {
            yaw = switch (face) {
                case NORTH -> 180f;
                case SOUTH -> 0f;
                case WEST -> 90f;
                default -> 270f; // EAST
            };
        }

        List<Block> hitbox = hitboxBlocks(origin, f, yaw, face == BlockFace.DOWN);
        // let protection plugins (WorldGuard, claims...) veto the placement like a normal block place
        org.bukkit.block.BlockState previous = origin.getState();
        org.bukkit.event.block.BlockPlaceEvent check = new org.bukkit.event.block.BlockPlaceEvent(origin, previous, clicked,
                hand, p, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(check);
        if (check.isCancelled() || !check.canBuild()) return;
        if (f.solid) {
            for (Block b : hitbox) {
                if (!replaceable(b)) return;
                if (!b.getWorld().getNearbyEntities(b.getBoundingBox(), en -> en instanceof LivingEntity).isEmpty()) return;
            }
        } else if (!replaceable(origin)) {
            return;
        }

        Location loc = origin.getLocation().add(0.5, face == BlockFace.DOWN ? 1.0 : 0.0, 0.5);
        loc.setYaw(yaw);
        ItemStack displayed = hand.clone();
        displayed.setAmount(1);

        ItemDisplay display = origin.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(displayed);
            d.setPersistent(true);
            ItemDisplay.ItemDisplayTransform transform = switch (f.entity.toLowerCase(Locale.ROOT)) {
                case "item_frame" -> ItemDisplay.ItemDisplayTransform.FIXED;
                case "item_display" -> parseTransform(item);
                default -> ItemDisplay.ItemDisplayTransform.HEAD; // armor_stand furniture models target the head slot
            };
            d.setItemDisplayTransform(transform);
            Transformation t = d.getTransformation();
            float yOffset = face == BlockFace.DOWN ? -0.5f : 0.5f; // model centred in its block
            Vector3f translation = new Vector3f(0, yOffset, 0);
            Quaternionf leftRot = transform == ItemDisplay.ItemDisplayTransform.FIXED
                    ? new Quaternionf().rotateY((float) Math.PI) : new Quaternionf();
            d.setTransformation(new Transformation(translation, leftRot, t.getScale(), t.getRightRotation()));
            if (f.lightLevel > 0) d.setBrightness(new Display.Brightness(f.lightLevel, f.lightLevel));
            PersistentDataContainer pdc = d.getPersistentDataContainer();
            pdc.set(FURNITURE, PersistentDataType.STRING, item.namespacedId());
        });

        List<String> keys = new ArrayList<>();
        if (f.solid) {
            for (Block b : hitbox) {
                b.setType(Material.BARRIER, false);
                keys.add(key(b));
            }
        } else {
            Interaction hit = origin.getWorld().spawn(origin.getLocation().add(0.5, 0, 0.5), Interaction.class, i -> {
                i.setInteractionWidth(Math.max(f.width, f.length));
                i.setInteractionHeight(f.height);
                i.setPersistent(true);
                i.getPersistentDataContainer().set(LINK, PersistentDataType.STRING, display.getUniqueId().toString());
            });
            display.getPersistentDataContainer().set(LINK, PersistentDataType.STRING, hit.getUniqueId().toString());
            if (f.lightLevel > 0 && origin.getType().isAir()) {
                origin.setType(Material.LIGHT, false);
                if (origin.getBlockData() instanceof Levelled lv) {
                    lv.setLevel(Math.min(15, f.lightLevel));
                    origin.setBlockData(lv, false);
                }
                keys.add(key(origin));
            }
        }
        display.getPersistentDataContainer().set(BLOCKS, PersistentDataType.STRING, String.join(";", keys));

        playSound(loc, f.placeSound);
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        p.swingMainHand();
    }

    private static ItemDisplay.ItemDisplayTransform parseTransform(CustomItem item) {
        String t = item.section.getString("behaviours.furniture.display_transformation.transform", "FIXED");
        try {
            return ItemDisplay.ItemDisplayTransform.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ItemDisplay.ItemDisplayTransform.FIXED;
        }
    }

    private static List<Block> hitboxBlocks(Block origin, CustomItem.FurnitureSettings f, float yaw, boolean ceiling) {
        List<Block> out = new ArrayList<>();
        int rot = Math.floorMod(Math.round(yaw / 90f), 4);
        int halfW = (f.width - 1) / 2;
        for (int y = 0; y < f.height; y++) {
            for (int w = -halfW; w < f.width - halfW; w++) {
                for (int l = 0; l < f.length; l++) {
                    int dx, dz;
                    switch (rot) {
                        case 1 -> { dx = -l; dz = w; }
                        case 2 -> { dx = -w; dz = -l; }
                        case 3 -> { dx = l; dz = -w; }
                        default -> { dx = w; dz = l; }
                    }
                    out.add(origin.getRelative(dx, ceiling ? -y : y + (int) Math.round(f.heightOffset), dz));
                }
            }
        }
        return out;
    }

    private static boolean replaceable(Block b) {
        Material m = b.getType();
        return m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS || m == Material.SNOW || m == Material.FERN || m == Material.DEAD_BUSH || m == Material.LIGHT;
    }

    private int countInChunk(Chunk chunk) {
        int n = 0;
        for (Entity en : chunk.getEntities()) {
            if (en instanceof ItemDisplay && en.getPersistentDataContainer().has(FURNITURE, PersistentDataType.STRING)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(ignoreCancelled = true)
    public void onBarrierHit(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.BARRIER) return;
        ItemDisplay d = findByBlock(b);
        if (d != null) {
            e.setCancelled(true);
            breakFurniture(d, e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreativeBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.BARRIER) return;
        ItemDisplay d = findByBlock(e.getBlock());
        if (d != null) {
            e.setCancelled(true);
            breakFurniture(d, e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractionHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction i) || !(e.getDamager() instanceof Player p)) return;
        String link = i.getPersistentDataContainer().get(LINK, PersistentDataType.STRING);
        if (link == null) return;
        e.setCancelled(true);
        Entity en = Bukkit.getEntity(UUID.fromString(link));
        if (en instanceof ItemDisplay d) breakFurniture(d, p);
        else i.remove();
    }

    private void breakFurniture(ItemDisplay d, Player p) {
        String id = d.getPersistentDataContainer().get(FURNITURE, PersistentDataType.STRING);
        CustomItem item = id == null ? null : factory.itemOf(d.getItemStack());
        CustomItem.FurnitureSettings f = item == null ? null : item.furniture;
        if (f != null && f.breakPermission != null && !p.hasPermission(f.breakPermission)) {
            p.sendMessage(ChatColor.RED + "Voce nao tem permissao para quebrar este movel.");
            return;
        }
        String blocks = d.getPersistentDataContainer().get(BLOCKS, PersistentDataType.STRING);
        String legacy = d.getPersistentDataContainer().get(LEGACY_LOC, PersistentDataType.STRING);
        if (legacy != null) blocks = blocks == null || blocks.isEmpty() ? legacy : blocks + ";" + legacy;
        if (blocks != null && !blocks.isEmpty()) {
            for (String k : blocks.split(";")) {
                Block b = fromKey(k);
                if (b != null && (b.getType() == Material.BARRIER || b.getType() == Material.LIGHT)) b.setType(Material.AIR, false);
            }
        }
        String link = d.getPersistentDataContainer().get(LINK, PersistentDataType.STRING);
        if (link != null) {
            Entity hit = Bukkit.getEntity(UUID.fromString(link));
            if (hit != null) hit.remove();
        }
        Location loc = d.getLocation();
        if (p.getGameMode() != GameMode.CREATIVE && (f == null || f.dropWhenMined)) {
            ItemStack drop = d.getItemStack();
            if (drop != null && !drop.getType().isAir()) loc.getWorld().dropItemNaturally(loc, drop);
        }
        playSound(loc, f == null ? "block.wood.break" : f.breakSound);
        d.remove();
    }

    private ItemDisplay findByBlock(Block b) {
        String k = key(b);
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 4, 4, 4)) {
            if (!(en instanceof ItemDisplay d)) continue;
            String legacy = d.getPersistentDataContainer().get(LEGACY_LOC, PersistentDataType.STRING);
            if (k.equals(legacy)) return d;
            String blocks = d.getPersistentDataContainer().get(BLOCKS, PersistentDataType.STRING);
            if (blocks == null) continue;
            for (String s : blocks.split(";")) if (s.equals(k)) return d;
        }
        return null;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static Block fromKey(String k) {
        String[] p = k.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    private static void playSound(Location loc, String name) {
        if (name == null || name.isBlank()) return;
        loc.getWorld().playSound(loc, name.contains(":") ? name : "minecraft:" + name, SoundCategory.BLOCKS, 1f, 1f);
    }

}
