package kr.gcb.remoterenderer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/remoterenderer.properties */
public final class RrConfig {
    /** Master switch. Off = every item display goes through the vanilla renderer. */
    public boolean enabled = true;
    /** Decode objmc textures and draw those models from the decoded data. */
    public boolean objmc = true;
    /** Skip re-resolving the item model of a display whose item stack did not change (static model types only). */
    public boolean cacheResolve = true;
    /** Only models with at least this many solid quads take the GPU path (0 = all). */
    public int minQuads = 0;
    /** Above 0: write a RR_STATS line to the log every this many seconds. */
    public int statsLogSeconds = 0;
    /** A baked model nobody drew for this many seconds is freed. */
    public int evictSeconds = 30;
    /** Draw even while a resource pack replaces the item shader (its effects on item displays are then lost). */
    public boolean ignoreCustomItemShaders = false;
    /** A resource pack's own item shader is run instanced (its effects stay); false: RemoteRenderer steps aside for such packs. */
    public boolean adaptCustomItemShaders = true;
    /** A model with at least this many quads is not drawn while its first bake runs (instead of the costly vanilla way). */
    public int hideWhileBakingQuads = 512;
    /** ... for at most this long; after that it is drawn the vanilla way until the bake is done. */
    public int hideWhileBakingMs = 500;
    /** Background threads baking new models (low priority). */
    public int bakeThreads = 2;
    /** Draw the rigs of resource packs (skinned meshes) instead of their bone displays. */
    public boolean rigs = true;
    /** Skip display models and rigs outside the view (vanilla draws display entities even behind the camera). */
    public boolean cullOffscreen = true;
    /** Spring joints (hair, skirts, ribbons) declared by rigs move with physics. */
    public boolean rigPhysics = true;
    /** With an Iris shader pack: keep working through vanilla render types (the pack's programs and shadows) instead of stepping aside. */
    public boolean shaderCompat = true;
    /** Rig textures larger than this (pixels per side) are halved until they fit, when loaded (0 = as they are). */
    public int rigTextureMaxSize = 2048;
    /** A rig texture no model used for this many seconds leaves the GPU (loaded again when needed). */
    public int rigTextureIdleSeconds = 60;

    public static RrConfig load(Path file) {
        RrConfig c = new RrConfig();
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                RemoteRenderer.LOG.warn("Could not read {}: {}", file, e.toString());
            }
        }
        c.enabled = bool(p, "enabled", c.enabled);
        c.objmc = bool(p, "objmc", c.objmc);
        c.cacheResolve = bool(p, "cacheResolve", c.cacheResolve);
        c.minQuads = integer(p, "minQuads", c.minQuads);
        c.statsLogSeconds = integer(p, "statsLogSeconds", c.statsLogSeconds);
        c.evictSeconds = Math.max(2, integer(p, "evictSeconds", c.evictSeconds));
        c.ignoreCustomItemShaders = bool(p, "ignoreCustomItemShaders", c.ignoreCustomItemShaders);
        c.adaptCustomItemShaders = bool(p, "adaptCustomItemShaders", c.adaptCustomItemShaders);
        c.adaptCustomItemShaders = Boolean.parseBoolean(System.getProperty("remoterenderer.adaptCustomItemShaders", Boolean.toString(c.adaptCustomItemShaders)));
        c.hideWhileBakingQuads = integer(p, "hideWhileBakingQuads", c.hideWhileBakingQuads);
        c.hideWhileBakingMs = integer(p, "hideWhileBakingMs", c.hideWhileBakingMs);
        c.bakeThreads = Math.clamp(integer(p, "bakeThreads", c.bakeThreads), 1, 8);
        c.rigs = bool(p, "rigs", c.rigs);
        c.cullOffscreen = bool(p, "cullOffscreen", c.cullOffscreen);
        c.rigPhysics = bool(p, "rigPhysics", c.rigPhysics);
        c.shaderCompat = bool(p, "shaderCompat", c.shaderCompat);
        c.shaderCompat = Boolean.parseBoolean(System.getProperty("remoterenderer.shaderCompat", Boolean.toString(c.shaderCompat)));
        c.cullOffscreen = Boolean.parseBoolean(System.getProperty("remoterenderer.cullOffscreen", Boolean.toString(c.cullOffscreen)));
        c.rigPhysics = Boolean.parseBoolean(System.getProperty("remoterenderer.rigPhysics", Boolean.toString(c.rigPhysics)));
        c.rigTextureMaxSize = Math.max(0, integer(p, "rigTextureMaxSize", c.rigTextureMaxSize));
        c.rigTextureIdleSeconds = Math.max(5, integer(p, "rigTextureIdleSeconds", c.rigTextureIdleSeconds));
        c.rigTextureMaxSize = Integer.getInteger("remoterenderer.rigTextureMaxSize", c.rigTextureMaxSize);
        c.rigTextureIdleSeconds = Integer.getInteger("remoterenderer.rigTextureIdleSeconds", c.rigTextureIdleSeconds);
        c.rigs = Boolean.parseBoolean(System.getProperty("remoterenderer.rigs", Boolean.toString(c.rigs)));
        c.ignoreCustomItemShaders = Boolean.parseBoolean(System.getProperty("remoterenderer.ignoreCustomItemShaders",
            Boolean.toString(c.ignoreCustomItemShaders)));
        // system properties win (handy for test runs): -Dremoterenderer.enabled=false
        c.enabled = Boolean.parseBoolean(System.getProperty("remoterenderer.enabled", Boolean.toString(c.enabled)));
        c.statsLogSeconds = Integer.getInteger("remoterenderer.statsLogSeconds", c.statsLogSeconds);
        if (!Files.exists(file)) {
            c.save(file);
        }
        return c;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# RemoteRenderer\n");
                w.write("# enabled: false = everything is drawn by the vanilla renderer\n");
                w.write("enabled=" + this.enabled + "\n");
                w.write("# objmc: draw objmc models (texture-encoded meshes) from decoded data\n");
                w.write("objmc=" + this.objmc + "\n");
                w.write("# cacheResolve: reuse the resolved item model while a display's item stays the same\n");
                w.write("cacheResolve=" + this.cacheResolve + "\n");
                w.write("# minQuads: only models with at least this many solid quads use the GPU path (0 = all)\n");
                w.write("minQuads=" + this.minQuads + "\n");
                w.write("# statsLogSeconds: >0 writes a RR_STATS line to the log this often\n");
                w.write("statsLogSeconds=" + this.statsLogSeconds + "\n");
                w.write("# evictSeconds: a baked model nobody drew for this many seconds is freed\n");
                w.write("evictSeconds=" + this.evictSeconds + "\n");
                w.write("# ignoreCustomItemShaders: true = keep drawing while a resource pack replaces the item shader\n");
                w.write("#   (by default RemoteRenderer steps aside then, so the pack's HUD / decal tricks on item displays keep working)\n");
                w.write("ignoreCustomItemShaders=" + this.ignoreCustomItemShaders + "\n");
                w.write("# adaptCustomItemShaders: run a resource pack's own item shader instanced (its materials / HUD tricks stay); false = step aside for such packs\n");
                w.write("adaptCustomItemShaders=" + this.adaptCustomItemShaders + "\n");
                w.write("# a model with at least hideWhileBakingQuads quads is not drawn while its first bake runs (at most hideWhileBakingMs);\n");
                w.write("#   drawing it the vanilla way for those frames would cost the stall the background bake avoids\n");
                w.write("hideWhileBakingQuads=" + this.hideWhileBakingQuads + "\n");
                w.write("hideWhileBakingMs=" + this.hideWhileBakingMs + "\n");
                w.write("# bakeThreads: low-priority background threads that bake new models (1..8)\n");
                w.write("bakeThreads=" + this.bakeThreads + "\n");
                w.write("# rigs: draw resource-pack rigs (assets/<ns>/remoterenderer/rigs/*.json) as skinned meshes instead of their bone displays\n");
                w.write("rigs=" + this.rigs + "\n");
                w.write("# cullOffscreen: skip display models and rigs outside the view (vanilla draws display entities even behind the camera)\n");
                w.write("cullOffscreen=" + this.cullOffscreen + "\n");
                w.write("# rigPhysics: spring joints (hair, skirts, ribbons) declared by rigs swing with physics\n");
                w.write("rigPhysics=" + this.rigPhysics + "\n");
                w.write("# shaderCompat: with an Iris shader pack, hand models and rigs to the pack's programs (its shadows too) instead of stepping aside\n");
                w.write("shaderCompat=" + this.shaderCompat + "\n");
                w.write("# rigTextureMaxSize: rig textures bigger than this are scaled down when loaded (0 = keep; 2048 = a quarter of the memory of 4096, the same picture at play distance)\n");
                w.write("rigTextureMaxSize=" + this.rigTextureMaxSize + "\n");
                w.write("# rigTextureIdleSeconds: a rig texture unused this long leaves the GPU\n");
                w.write("rigTextureIdleSeconds=" + this.rigTextureIdleSeconds + "\n");
            }
        } catch (IOException e) {
            RemoteRenderer.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
