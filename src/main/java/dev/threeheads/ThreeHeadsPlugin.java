package dev.threeheads;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class ThreeHeadsPlugin extends JavaPlugin implements Listener {

    /** The two extra heads of one dragon: a model (ItemDisplay) and a hitbox (Slime) for each side. */
    private static final class Heads {
        final EnderDragon dragon;
        final ItemDisplay visualLeft;
        final ItemDisplay visualRight;
        final Slime hitLeft;
        final Slime hitRight;

        Heads(EnderDragon dragon, ItemDisplay vl, ItemDisplay vr, Slime hl, Slime hr) {
            this.dragon = dragon;
            this.visualLeft = vl;
            this.visualRight = vr;
            this.hitLeft = hl;
            this.hitRight = hr;
        }

        boolean valid() {
            return visualLeft.isValid() && visualRight.isValid() && hitLeft.isValid() && hitRight.isValid();
        }

        void remove() {
            kill(visualLeft);
            kill(visualRight);
            kill(hitLeft);
            kill(hitRight);
        }

        private static void kill(Entity e) {
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
    }

    private final Map<UUID, Heads> heads = new HashMap<>();
    private NamespacedKey key;
    private BukkitTask task;
    private int counter;

    private double spacing;
    private double scale;
    private double yOffset;
    private double forwardOffset;
    private int hitboxSize;
    private boolean flip;
    private boolean onlyPlayers;
    private double damageMultiplier;
    private boolean allWorlds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        key = new NamespacedKey(this, "head_of");
        removeLeftovers();
        Bukkit.getPluginManager().registerEvents(this, this);
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        for (Heads h : heads.values()) {
            h.remove();
        }
        heads.clear();
    }

    private void readConfig() {
        spacing = getConfig().getDouble("spacing", 3.0);
        scale = getConfig().getDouble("scale", 2.5);
        yOffset = getConfig().getDouble("y-offset", 0.0);
        forwardOffset = getConfig().getDouble("forward-offset", 0.0);
        hitboxSize = Math.max(1, getConfig().getInt("hitbox-size", 3));
        flip = getConfig().getBoolean("flip-facing", false);
        onlyPlayers = getConfig().getBoolean("only-players", true);
        damageMultiplier = getConfig().getDouble("damage-multiplier", 1.0);
        allWorlds = getConfig().getBoolean("all-worlds", false);
    }

    private void removeLeftovers() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClasses(ItemDisplay.class, Slime.class)) {
                if (e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                    e.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        if (counter++ % 20 == 0) {
            discover();
        }
        Iterator<Map.Entry<UUID, Heads>> it = heads.entrySet().iterator();
        while (it.hasNext()) {
            Heads h = it.next().getValue();
            if (!h.dragon.isValid() || h.dragon.isDead() || !h.valid()) {
                h.remove();
                it.remove();
                continue;
            }
            update(h);
        }
    }

    /** Looks for dragons that do not have extra heads yet. */
    private void discover() {
        for (World w : Bukkit.getWorlds()) {
            if (!allWorlds && w.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) {
                if (d.isDead() || !d.isValid() || heads.containsKey(d.getUniqueId())) {
                    continue;
                }
                Heads h = spawn(d);
                if (h != null) {
                    heads.put(d.getUniqueId(), h);
                }
            }
        }
    }

    private Heads spawn(EnderDragon d) {
        Location at = d.getLocation();
        ItemDisplay vl = spawnVisual(at, d);
        ItemDisplay vr = spawnVisual(at, d);
        Slime hl = spawnHitbox(at, d);
        Slime hr = spawnHitbox(at, d);
        Heads h = new Heads(d, vl, vr, hl, hr);
        if (!h.valid()) {
            // something (another plugin, region flag...) blocked the spawn
            h.remove();
            return null;
        }
        return h;
    }

    private ItemDisplay spawnVisual(Location at, EnderDragon d) {
        return at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.DRAGON_HEAD));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            e.setTeleportDuration(2);
            e.setBrightness(new Display.Brightness(12, 12));
            e.setViewRange(4.0f);
            e.setPersistent(false);
            e.setTransformation(transformation());
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.getUniqueId().toString());
        });
    }

    private Slime spawnHitbox(Location at, EnderDragon d) {
        return at.getWorld().spawn(at, Slime.class, s -> {
            s.setSize(hitboxSize);
            s.setAI(false);
            s.setGravity(false);
            s.setSilent(true);
            s.setInvisible(true);
            s.setCollidable(false);
            s.setPersistent(false);
            s.setRemoveWhenFarAway(false);
            s.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.getUniqueId().toString());
        });
    }

    private Transformation transformation() {
        float s = (float) scale;
        // the skull model sits in the lower half of its block, so lift it to centre it on the head position
        Vector3f translation = new Vector3f(0f, (float) (0.25 * scale + yOffset), 0f);
        AxisAngle4f left = flip ? new AxisAngle4f((float) Math.PI, 0f, 1f, 0f) : new AxisAngle4f();
        return new Transformation(translation, left, new Vector3f(s, s, s), new AxisAngle4f());
    }

    // ------------------------------------------------------------------ following the real head

    private ComplexEntityPart findPart(EnderDragon d, double size) {
        for (ComplexEntityPart p : d.getParts()) {
            BoundingBox b = p.getBoundingBox();
            if (Math.abs(b.getWidthX() - size) < 0.05 && Math.abs(b.getHeight() - size) < 0.05) {
                return p;
            }
        }
        return null;
    }

    private Vector baseOf(ComplexEntityPart p) {
        BoundingBox b = p.getBoundingBox();
        return new Vector(b.getCenterX(), b.getMinY(), b.getCenterZ());
    }

    private Vector bodyForward(EnderDragon d) {
        Location l = d.getLocation();
        l.setPitch(0f);
        l.setYaw(l.getYaw() + 180f);
        return l.getDirection();
    }

    private void update(Heads h) {
        EnderDragon d = h.dragon;
        ComplexEntityPart head = findPart(d, 1.0);   // the real head hit box is 1 x 1
        if (head == null) {
            return;
        }
        ComplexEntityPart neck = findPart(d, 3.0);   // the neck hit box is 3 x 3

        Vector headBase = baseOf(head);

        // The neck part sits exactly 1 block behind the head, on the line the head points along.
        Vector dir = null;
        if (neck != null) {
            Vector v = headBase.clone().subtract(baseOf(neck));
            if (v.lengthSquared() > 0.25) {
                dir = v.normalize();
            }
        }
        if (dir == null) {
            dir = bodyForward(d);
        }

        Vector center = headBase.clone().add(new Vector(0, 0.5, 0)).add(dir.clone().multiply(forwardOffset));

        Vector side = new Vector(dir.getZ(), 0, -dir.getX());
        if (side.lengthSquared() < 1.0E-4) {
            Vector f = bodyForward(d);
            side = new Vector(f.getZ(), 0, -f.getX());
        }
        side.normalize().multiply(spacing);

        World w = d.getWorld();
        place(w, h.visualLeft, h.hitLeft, center.clone().add(side), dir);
        place(w, h.visualRight, h.hitRight, center.clone().subtract(side), dir);
    }

    private void place(World w, ItemDisplay visual, Slime hit, Vector p, Vector dir) {
        Location vl = new Location(w, p.getX(), p.getY(), p.getZ());
        vl.setDirection(dir);
        visual.teleport(vl);

        Location hl = new Location(w, p.getX(), p.getY() - hit.getHeight() / 2.0, p.getZ());
        hit.teleport(hl);
    }

    // ------------------------------------------------------------------ hitting the extra heads

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitboxDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Slime slime)) {
            return;
        }
        String id = slime.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        double amount = e.getFinalDamage();
        e.setCancelled(true); // the hitbox itself never takes damage, it is passed on to the dragon

        if (!(e instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity source = byEntity.getDamager();
        if (source instanceof Projectile p && p.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof EnderDragon) {
            return;
        }
        if (onlyPlayers && !(source instanceof Player)) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!(Bukkit.getEntity(uuid) instanceof EnderDragon dragon) || dragon.isDead()) {
            return;
        }
        applyDamage(dragon, amount * damageMultiplier, source);
    }

    private void applyDamage(EnderDragon dragon, double damage, Entity source) {
        if (damage <= 0 || dragon.getPhase() == EnderDragon.Phase.DYING) {
            return;
        }
        // A tiny real hit first, so the game credits the attacker and the dragon reacts like it was hit.
        dragon.setNoDamageTicks(0);
        dragon.damage(0.02, source);

        double newHealth = dragon.getHealth() - damage;
        if (newHealth <= 1.0) {
            // same thing the game does when the dragon would die: 1 hp left and it flies to the portal to die
            dragon.setHealth(1.0);
            dragon.setPhase(EnderDragon.Phase.DYING);
        } else {
            dragon.setHealth(newHealth);
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof EnderDragon d) {
            Heads h = heads.remove(d.getUniqueId());
            if (h != null) {
                h.remove();
            }
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            readConfig();
            for (Heads h : heads.values()) {
                h.remove();
            }
            heads.clear();   // new heads are created within a second
            sender.sendMessage("ThreeHeadsDragon reloaded.");
            return true;
        }
        sender.sendMessage("Usage: /threeheads reload");
        return true;
    }
}
