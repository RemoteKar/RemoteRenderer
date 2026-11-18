package kr.gcb.meprobe;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin for the RemoteRenderer tests: puts ModelEngine models on real mobs from the console.
 *
 *   meprobe spawn <model> <x> <y> <z> [count] [spacing] [yaw]   invisible, AI-less pigs (tag "probe") wearing the model
 *   meprobe anim <animation>                                    loop an animation on every probe
 *   meprobe clear
 * Turn their heads with vanilla: execute as @e[tag=probe] at @s run tp @s ~ ~ ~ ~15 20
 */
public final class MeProbe extends JavaPlugin {
    private final List<Entity> bodies = new ArrayList<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0]) {
            case "spawn" -> {
                World w = Bukkit.getWorlds().getFirst();
                double x = Double.parseDouble(args[2]), y = Double.parseDouble(args[3]), z = Double.parseDouble(args[4]);
                int count = args.length > 5 ? Integer.parseInt(args[5]) : 1;
                double spacing = args.length > 6 ? Double.parseDouble(args[6]) : 2.0;
                float yaw = args.length > 7 ? Float.parseFloat(args[7]) : 180.0F;
                int ok = 0;
                for (int i = 0; i < count; i++) {
                    Pig pig = w.spawn(new Location(w, x + (i % 8) * spacing, y, z + (i / 8) * spacing, yaw, 0), Pig.class, p -> {
                        p.setAI(false);
                        p.setSilent(true);
                        p.setInvisible(true);
                        p.setPersistent(true);
                        p.setRemoveWhenFarAway(false);
                        p.addScoreboardTag("probe");
                    });
                    bodies.add(pig);
                    ActiveModel model = ModelEngineAPI.createActiveModel(args[1]);
                    if (model == null) {
                        getLogger().warning("MEPROBE no model " + args[1]);
                        return true;
                    }
                    ModeledEntity modeled = ModelEngineAPI.createModeledEntity(pig);
                    if (modeled.addModel(model, true).isPresent()) {
                        ok++;
                    }
                }
                getLogger().info("MEPROBE spawned " + ok + " x " + args[1]);
            }
            case "anim" -> {
                int ok = 0;
                for (Entity e : bodies) {
                    ModeledEntity modeled = ModelEngineAPI.getModeledEntity(e);
                    if (modeled == null) {
                        continue;
                    }
                    for (ActiveModel m : modeled.getModels().values()) {
                        if (m.getAnimationHandler().playAnimation(args[1], 0.1, 0.1, 1.0, true) != null) {
                            ok++;
                        }
                    }
                }
                getLogger().info("MEPROBE anim " + args[1] + " on " + ok);
            }
            case "clear" -> {
                for (Entity e : bodies) {
                    ModelEngineAPI.removeModeledEntity(e);
                    e.remove();
                }
                bodies.clear();
                getLogger().info("MEPROBE cleared");
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
