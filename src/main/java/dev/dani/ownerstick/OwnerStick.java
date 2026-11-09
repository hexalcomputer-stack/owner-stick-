package dev.dani.ownerstick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

public class OwnerStick extends JavaPlugin implements Listener, CommandExecutor {

    private static final String[] MODES = {
        "Lightning", "Chain Lightning", "Slam", "Launch", "Freeze", "Meteor",
        "Black Hole", "Mob Rain", "Swap", "Vanish", "Kick (sneak + hit)", "Ban (sneak + hit)"
    };
    private static final int BAN_MINUTES = 10;

    private final Map<UUID, Integer> mode = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();
    private NamespacedKey key;

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "ownerstick");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ownerstick").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p) || !p.hasPermission("ownerstick.use")) {
            s.sendMessage("No permission.");
            return true;
        }
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta meta = stick.getItemMeta();
        meta.displayName(Component.text("Owner Stick", NamedTextColor.GOLD));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stick.setItemMeta(meta);
        p.getInventory().addItem(stick);
        p.sendMessage("Owner Stick given. Sneak + right-click to switch power. Left-click a target to use it.");
        return true;
    }

    private boolean isStick(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastUse.getOrDefault(p.getUniqueId(), 0L) < 250) return true;
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    // Right-click: sneak switches power, plain right-click in the air toggles vanish when that power is selected.
    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!isStick(p.getInventory().getItemInMainHand())) return;
        if (!p.hasPermission("ownerstick.use")) return;

        Action act = e.getAction();
        if (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            if (debounce(p)) return;
            int m = mode.getOrDefault(p.getUniqueId(), 0);
            if (p.isSneaking()) {
                m = (m + 1) % MODES.length;
                mode.put(p.getUniqueId(), m);
                p.sendActionBar(Component.text("Power: " + MODES[m], NamedTextColor.GOLD));
            } else if (m == 9) {
                toggleVanish(p);
            }
            return;
        }
        if (act == Action.LEFT_CLICK_AIR || act == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);
            if (debounce(p)) return;
            Entity t = p.getTargetEntity(40);
            if (t instanceof LivingEntity le && !(t instanceof ArmorStand)) fire(p, le);
            else p.sendActionBar(Component.text("No target in sight.", NamedTextColor.RED));
        }
    }

    // Melee hit on a target also fires the power, and the normal damage is cancelled.
    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isStick(p.getInventory().getItemInMainHand())) return;
        if (!p.hasPermission("ownerstick.use")) return;
        e.setCancelled(true);
        if (debounce(p)) return;
        if (e.getEntity() instanceof LivingEntity le && !(le instanceof ArmorStand)) fire(p, le);
    }

    private void fire(Player p, LivingEntity t) {
        int m = mode.getOrDefault(p.getUniqueId(), 0);
        switch (m) {
            case 0 -> lightning(p, t);
            case 1 -> chain(p, t);
            case 2 -> slam(t);
            case 3 -> t.setVelocity(new Vector(0, 2.5, 0));
            case 4 -> t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 10));
            case 5 -> meteor(t);
            case 6 -> blackHole(p, t);
            case 7 -> mobRain(t);
            case 8 -> swap(p, t);
            case 9 -> p.sendActionBar(Component.text("Vanish: right-click in the air.", NamedTextColor.YELLOW));
            case 10 -> kick(p, t);
            case 11 -> ban(p, t);
            default -> { }
        }
    }

    private void lightning(Player owner, LivingEntity t) {
        t.getWorld().strikeLightningEffect(t.getLocation());
        t.damage(8.0, owner);
    }

    private void chain(Player owner, LivingEntity start) {
        Set<UUID> hit = new HashSet<>();
        LivingEntity cur = start;
        for (int i = 0; i < 6 && cur != null; i++) {
            final LivingEntity t = cur;
            hit.add(t.getUniqueId());
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!t.isValid()) return;
                t.getWorld().strikeLightningEffect(t.getLocation());
                t.damage(6.0, owner);
            }, i * 6L);
            cur = nearest(cur, hit, owner);
        }
    }

    private LivingEntity nearest(LivingEntity from, Set<UUID> hit, Player owner) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity en : from.getNearbyEntities(8, 8, 8)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (en.equals(owner) || hit.contains(en.getUniqueId())) continue;
            double d = en.getLocation().distanceSquared(from.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    private void slam(LivingEntity t) {
        t.setVelocity(new Vector(0, 1.6, 0));
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!t.isValid()) return;
            t.setVelocity(new Vector(0, -3.0, 0));
            t.getWorld().playSound(t.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            t.getWorld().spawnParticle(Particle.EXPLOSION, t.getLocation(), 1);
        }, 14L);
    }

    private void meteor(LivingEntity t) {
        Location above = t.getLocation().add(0, 25, 0);
        Fireball fb = t.getWorld().spawn(above, Fireball.class);
        fb.setDirection(new Vector(0, -1, 0));
        fb.setYield(0f);
        fb.setIsIncendiary(false);
    }

    private void blackHole(Player owner, LivingEntity t) {
        Location center = t.getLocation();
        for (int i = 0; i < 20; i++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                center.getWorld().spawnParticle(Particle.PORTAL, center, 40, 1, 1, 1, 0.5);
                for (Entity en : center.getWorld().getNearbyEntities(center, 10, 10, 10)) {
                    if (!(en instanceof LivingEntity) || en.equals(owner) || en instanceof ArmorStand) continue;
                    Vector pull = center.toVector().subtract(en.getLocation().toVector()).normalize().multiply(0.6);
                    en.setVelocity(pull);
                }
            }, i * 3L);
        }
    }

    private void mobRain(LivingEntity t) {
        Random r = new Random();
        for (int i = 0; i < 5; i++) {
            Location l = t.getLocation().add(r.nextInt(5) - 2, 6, r.nextInt(5) - 2);
            t.getWorld().spawn(l, Zombie.class);
        }
    }

    private void swap(Player p, LivingEntity t) {
        Location a = p.getLocation(), b = t.getLocation();
        p.teleport(b);
        t.teleport(a);
    }

    private void toggleVanish(Player p) {
        UUID id = p.getUniqueId();
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.05);
        if (vanished.remove(id)) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.sendActionBar(Component.text("Visible", NamedTextColor.GRAY));
        } else {
            vanished.add(id);
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
            p.sendActionBar(Component.text("Vanished", NamedTextColor.GRAY));
        }
    }

    private void kick(Player owner, LivingEntity t) {
        if (!(t instanceof Player target)) {
            owner.sendActionBar(Component.text("Kick only works on players.", NamedTextColor.RED));
            return;
        }
        if (!owner.isSneaking()) {
            owner.sendActionBar(Component.text("Sneak while you hit to confirm the kick.", NamedTextColor.RED));
            return;
        }
        target.getWorld().strikeLightningEffect(target.getLocation());
        getServer().getScheduler().runTaskLater(this, () ->
            target.kick(Component.text("You were kicked by the owner.")), 15L);
    }

    private void ban(Player owner, LivingEntity t) {
        if (!(t instanceof Player target)) {
            owner.sendActionBar(Component.text("Ban only works on players.", NamedTextColor.RED));
            return;
        }
        if (!owner.isSneaking()) {
            owner.sendActionBar(Component.text("Sneak while you hit to confirm the ban.", NamedTextColor.RED));
            return;
        }
        target.getWorld().strikeLightningEffect(target.getLocation());
        getServer().getScheduler().runTaskLater(this, () -> {
            target.ban("Banned by the owner.", Duration.ofMinutes(BAN_MINUTES), owner.getName());
            target.kick(Component.text("You were banned for " + BAN_MINUTES + " minutes."));
        }, 15L);
    }
}
