package kr.gcb.dmprobe;

import java.util.ArrayList;
import java.util.List;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitEntity;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin for the RemoteRenderer tests: puts BetterModel models on real mobs (EntityTracker with the default
 * modifier, so BetterModel's own hit flash is on) from the console, which /bm spawn does not allow.
 *
 *   dmprobe spawn <model> <x> <y> <z> [count] [spacing]   invisible, AI-less pigs with 1000 hp wearing the model
 *   dmprobe anim <animation>                              loop an animation on every probe
 *   dmprobe clear
 * Hurt them with vanilla: execute as @e[tag=probe] run damage @s 1
 */
public final class DmProbe extends JavaPlugin {
    private final List<EntityTracker> trackers = new ArrayList<>();
    private final List<Entity> bodies = new ArrayList<>();
    private org.bukkit.scheduler.BukkitTask swingTask;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0]) {
            case "spawn" -> {
                ModelRenderer renderer = BetterModel.modelOrNull(args[1]);
                if (renderer == null) {
                    getLogger().warning("DMPROBE no model " + args[1]);
                    return true;
                }
                World w = Bukkit.getWorlds().getFirst();
                double x = Double.parseDouble(args[2]), y = Double.parseDouble(args[3]), z = Double.parseDouble(args[4]);
                int count = args.length > 5 ? Integer.parseInt(args[5]) : 1;
                double spacing = args.length > 6 ? Double.parseDouble(args[6]) : 3.0;
                float yaw = args.length > 7 ? Float.parseFloat(args[7]) : 180.0F;
                for (int i = 0; i < count; i++) {
                    Pig pig = w.spawn(new Location(w, x + (i % 8) * spacing, y, z + (i / 8) * spacing, yaw, 0), Pig.class, p -> {
                        p.setAI(false);
                        p.setSilent(true);
                        p.setInvisible(true);
                        p.setPersistent(true);
                        p.setRemoveWhenFarAway(false);
                        p.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1000.0);
                        p.setHealth(1000.0);
                        p.addScoreboardTag("probe");
                    });
                    bodies.add(pig);
                    trackers.add(renderer.create(new BukkitEntity(pig), TrackerModifier.DEFAULT));
                }
                getLogger().info("DMPROBE spawned " + count + " x " + args[1]);
            }
            case "anim" -> {
                int ok = 0;
                for (EntityTracker t : trackers) {
                    if (!t.isClosed() && t.animate(args[1], AnimationModifier.DEFAULT)) {
                        ok++;
                    }
                }
                getLogger().info("DMPROBE anim " + args[1] + " on " + ok);
            }
            case "tint" -> {
                // the update BetterModel's hit flash sends (EntityTracker.damageTint: tint(damageTintValue), 10 ticks later
                // previousTint), held until "tint off" so a screenshot can catch it
                boolean off = args.length > 1 && args[1].equals("off");
                for (EntityTracker t : trackers) {
                    if (!t.isClosed()) {
                        t.update(off ? TrackerUpdateAction.previousTint() : TrackerUpdateAction.tint(t.damageTintValue()));
                    }
                }
                getLogger().info("DMPROBE tint " + (off ? "off" : Integer.toHexString(trackers.isEmpty() ? 0 : trackers.getFirst().damageTintValue())));
            }
            case "display" -> {
                // dmprobe display <item_model> <x> <y> <z> [yaw]: an item display riding an invisible pig (a rig anchor that is
                // not a BetterModel model); its transformation lowers it to the pig's feet. swing moves it too.
                World w = Bukkit.getWorlds().getFirst();
                double x = Double.parseDouble(args[2]), y = Double.parseDouble(args[3]), z = Double.parseDouble(args[4]);
                float yaw = args.length > 5 ? Float.parseFloat(args[5]) : 0.0F;
                Location at = new Location(w, x, y, z, yaw, 0);
                Pig pig = w.spawn(at, Pig.class, p -> {
                    p.setAI(false);
                    p.setSilent(true);
                    p.setInvisible(true);
                    p.setPersistent(true);
                    p.setRemoveWhenFarAway(false);
                    p.addScoreboardTag("probe");
                });
                org.bukkit.entity.ItemDisplay d = w.spawn(at, org.bukkit.entity.ItemDisplay.class, e -> {
                    org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
                    it.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(args[1]));
                    e.setItemStack(it);
                    e.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
                    e.setTransformationMatrix(new org.joml.Matrix4f().translation(0f, -0.86875f, 0f));
                    e.setInterpolationDuration(1);
                    e.addScoreboardTag("probe");
                });
                pig.addPassenger(d);
                bodies.add(pig);
                bodies.add(d);
                getLogger().info("DMPROBE display " + args[1]);
            }
            case "swing" -> {
                // dmprobe swing <amplitude blocks> <period ticks>: every body slides back and forth along x (0 0 stops)
                double amp = Double.parseDouble(args[1]);
                int period = Integer.parseInt(args[2]);
                if (swingTask != null) {
                    swingTask.cancel();
                    swingTask = null;
                }
                if (amp > 0 && period > 0) {
                    List<Location> base = new ArrayList<>();
                    for (Entity e : bodies) {
                        base.add(e.getLocation().clone());
                    }
                    int[] tick = {0};
                    swingTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                        double off = amp * Math.sin(2 * Math.PI * tick[0]++ / period);
                        for (int i = 0; i < bodies.size() && i < base.size(); i++) {
                            Entity e = bodies.get(i);
                            if (!e.isInsideVehicle()) {
                                e.teleport(base.get(i).clone().add(off, 0, 0), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                            }
                        }
                    }, 1L, 1L);
                }
                getLogger().info("DMPROBE swing " + amp + " " + period);
            }
            case "clear" -> {
                trackers.forEach(t -> {
                    if (!t.isClosed()) {
                        t.close();
                    }
                });
                trackers.clear();
                bodies.forEach(Entity::remove);
                bodies.clear();
                getLogger().info("DMPROBE cleared");
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
