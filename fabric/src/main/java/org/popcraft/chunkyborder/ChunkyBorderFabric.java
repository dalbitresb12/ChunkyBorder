package org.popcraft.chunkyborder;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.platform.FabricPlayer;
import org.popcraft.chunky.platform.FabricWorld;
import org.popcraft.chunky.platform.Player;
import org.popcraft.chunky.platform.World;
import org.popcraft.chunky.platform.util.Vector3;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.util.Translator;
import org.popcraft.chunkyborder.command.BorderCommand;
import org.popcraft.chunkyborder.event.border.BorderChangeEvent;
import org.popcraft.chunkyborder.platform.Config;
import org.popcraft.chunkyborder.platform.MapIntegrationLoader;
import org.popcraft.chunkyborder.util.BorderColor;
import org.popcraft.chunkyborder.util.Particles;
import org.popcraft.chunkyborder.util.PluginMessage;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class ChunkyBorderFabric implements ModInitializer {
    private static final Identifier PLAY_BORDER_PACKET_ID = new Identifier("chunky", "border");
    private ChunkyBorder chunkyBorder;
    private boolean registered;

    @Override
    public void onInitialize() {
        final Chunky chunky = ChunkyProvider.get();
        final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("chunkyborder/config.json");
        final Config config = new FabricConfig(configPath);
        final MapIntegrationLoader mapIntegrationLoader = new FabricMapIntegrationLoader();
        this.chunkyBorder = new ChunkyBorder(chunky, config, mapIntegrationLoader);
        Translator.addCustomTranslation("custom_border_message", config.message());
        BorderColor.parseColor(config.visualizerColor());
        new BorderInitializationTask(chunkyBorder).run();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            for (final World world : chunkyBorder.getChunky().getServer().getWorlds()) {
                final Shape shape = chunkyBorder.getBorder(world.getName()).map(BorderData::getBorder).orElse(null);
                sendBorderPacket(List.of(handler.player), world, shape);
            }
        });
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (!registered) {
                chunkyBorder.getChunky().getEventBus().subscribe(BorderChangeEvent.class, e -> sendBorderPacket(server.getPlayerManager().getPlayerList(), e.world(), e.shape()));
                registered = true;
            }
            if (channels.contains(PLAY_BORDER_PACKET_ID)) {
                chunkyBorder.getPlayerData(handler.player.getUuid()).setUsingMod(true);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(minecraftServer -> chunkyBorder.disable());
        final long checkInterval = Math.max(1, chunkyBorder.getConfig().checkInterval());
        final BorderCheckTask borderCheckTask = new BorderCheckTask(chunkyBorder);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % checkInterval == 0) {
                borderCheckTask.run();
            }
        });
        chunkyBorder.getChunky().getCommands().put("border", new BorderCommand(chunkyBorder));
        startVisualizer();
    }

    private void startVisualizer() {
        final boolean visualizerEnabled = chunkyBorder.getConfig().visualizerEnabled();
        if (!visualizerEnabled) {
            return;
        }
        final int maxRange = chunkyBorder.getConfig().visualizerRange();
        Particles.setMaxDistance(maxRange);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            final long tick = server.getTicks();
            server.getPlayerManager().getPlayerList().forEach(fabricPlayer -> {
                final ServerWorld serverWorld = fabricPlayer.getServerWorld();
                final World world = new FabricWorld(serverWorld);
                final Player player = new FabricPlayer(fabricPlayer);
                final Shape border = chunkyBorder.getBorder(world.getName()).map(BorderData::getBorder).orElse(null);
                final boolean isUsingMod = chunkyBorder.getPlayerData(player.getUUID()).isUsingMod();
                if (border != null && !isUsingMod) {
                    final List<Vector3> particleLocations = Particles.at(player, border, (tick % 20) / 20d);
                    final Vector3f visualizerColor = new Vector3f(BorderColor.getRGB());
                    for (final Vector3 location : particleLocations) {
                        final BlockPos pos = BlockPos.ofFloored(location.getX(), location.getY(), location.getZ());
                        final boolean fullyOccluded = serverWorld.getBlockState(pos).isOpaqueFullCube(serverWorld, pos)
                                && serverWorld.getBlockState(pos.north()).isOpaqueFullCube(serverWorld, pos.north())
                                && serverWorld.getBlockState(pos.east()).isOpaqueFullCube(serverWorld, pos.east())
                                && serverWorld.getBlockState(pos.south()).isOpaqueFullCube(serverWorld, pos.south())
                                && serverWorld.getBlockState(pos.west()).isOpaqueFullCube(serverWorld, pos.west());
                        if (!fullyOccluded) {
                            serverWorld.spawnParticles(fabricPlayer, new DustParticleEffect(visualizerColor, 1f), false, location.getX(), location.getY(), location.getZ(), 1, 0d, 0d, 0d, 0d);
                        }
                    }
                }
            });
        });
    }

    private void sendBorderPacket(final Collection<ServerPlayerEntity> players, final World world, final Shape shape) {
        try {
            Packet<?> packet;
            try {
                // Backwards compatibility for 1.20.0 - 1.20.1
                final Class<?> oldCustomPayloadClass = Class.forName("net.minecraft.class_2658"); // net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket
                final MethodType oldMethodType = MethodType.methodType(void.class, Identifier.class, PacketByteBuf.class);
                final MethodHandle oldConstructor = MethodHandles.publicLookup().findConstructor(oldCustomPayloadClass, oldMethodType);
                // Using the old constructor
                final PacketByteBuf data = new PacketByteBuf(Unpooled.wrappedBuffer(PluginMessage.writeBorderData(world, shape)));
                try {
                    packet = (Packet<?>) oldConstructor.invoke(PLAY_BORDER_PACKET_ID, data);
                } catch (Throwable e) {
                    e.printStackTrace();
                    return;
                }
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                // Using the new constructor
                final PacketByteBuf data = new PacketByteBuf(Unpooled.buffer())
                        .writeIdentifier(PLAY_BORDER_PACKET_ID)
                        .writeBytes(PluginMessage.writeBorderData(world, shape));
                packet = new net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket(data);
            }
            for (final ServerPlayerEntity player : players) {
                player.networkHandler.sendPacket(packet);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
