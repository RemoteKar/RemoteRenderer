package kr.gcb.remoterenderer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import kr.gcb.remoterenderer.mesh.MeshCache;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/** /remoterenderer [on|off|stats|clear|minquads <n>] - client side only (needs Fabric API's command module). */
final class RrCommands {
    private RrCommands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
            ClientCommands.literal("remoterenderer")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(status());
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    RemoteRenderer.setEnabled(true);
                    save();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.on"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    RemoteRenderer.setEnabled(false);
                    save();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.off"));
                    return 1;
                }))
                .then(ClientCommands.literal("menu").executes(ctx -> {
                    RemoteRenderer.openMenu = true;
                    return 1;
                }))
                .then(ClientCommands.literal("stats").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(RemoteRenderer.STATS.line()));
                    return 1;
                }))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    MeshCache.requestClear();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.clear"));
                    return 1;
                }))
                .then(ClientCommands.literal("rigs").executes(ctx -> {
                    StringBuilder sb = new StringBuilder("RemoteRenderer rigs (" + (RemoteRenderer.config.rigs ? "on" : "off") + "):");
                    for (kr.gcb.remoterenderer.rig.RigManager.Rig r : kr.gcb.remoterenderer.rig.RigManager.rigs()) {
                        sb.append("\n ").append(r.def.id).append(" [").append(r.source).append("] ").append(r.state())
                            .append(r.def.boneGeometry() ? " bones" : " mesh").append(" bones=").append(r.def.bones.size())
                            .append(" joints=").append(r.def.joints.size()).append(" drawn=").append(r.lastInstances)
                            .append(r.error() != null ? " " + r.error() : "");
                    }
                    ctx.getSource().sendFeedback(Component.literal(sb.toString()));
                    return 1;
                }).then(ClientCommands.literal("play").executes(ctx -> {
                    kr.gcb.remoterenderer.render.RigRenderer.forcedClip = null;
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.play.own"));
                    return 1;
                }).then(ClientCommands.argument("clip", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                    String clip = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "clip");
                    kr.gcb.remoterenderer.render.RigRenderer.forcedClip = clip;
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.play", clip));
                    return 1;
                }))).then(ClientCommands.literal("on").executes(ctx -> {
                    RemoteRenderer.config.rigs = true;
                    save();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.rigs.on"));
                    return 1;
                })).then(ClientCommands.literal("off").executes(ctx -> {
                    RemoteRenderer.config.rigs = false;
                    save();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.rigs.off"));
                    return 1;
                })))
                .then(ClientCommands.literal("minquads").then(ClientCommands.argument("n", IntegerArgumentType.integer(0)).executes(ctx -> {
                    RemoteRenderer.config.minQuads = IntegerArgumentType.getInteger(ctx, "n");
                    MeshCache.requestClear();
                    save();
                    ctx.getSource().sendFeedback(Component.translatable("remoterenderer.cmd.minquads", RemoteRenderer.config.minQuads));
                    return 1;
                })))
        ));
    }

    private static Component status() {
        String reason = RemoteRenderer.disabledReason();
        Component why = reason != null ? Component.literal(reason) : null;
        if (why == null && RemoteRenderer.customItemShader() != null && !RemoteRenderer.config.ignoreCustomItemShaders
            && !kr.gcb.remoterenderer.render.PackShaders.active()) {
            why = Component.translatable("remoterenderer.menu.state.customShader", RemoteRenderer.customItemShader());
        }
        Component state = Component.translatable(RemoteRenderer.active() ? "remoterenderer.menu.state.on" : "remoterenderer.menu.state.off");
        return Component.translatable("remoterenderer.cmd.status", state, why != null ? Component.literal(" (").append(why).append(")") : Component.empty(),
            RemoteRenderer.STATS.line());
    }

    private static void save() {
        if (RemoteRenderer.configFile != null) {
            RemoteRenderer.config.save(RemoteRenderer.configFile);
        }
    }
}
