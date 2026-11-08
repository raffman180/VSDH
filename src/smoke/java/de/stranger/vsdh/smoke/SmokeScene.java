package de.stranger.vsdh.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import com.braffolk.dhvulkan.api.ApiDhIntegration;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development source set only; never included in the release JAR. */
@EventBusSubscriber(modid = "vsdh", value = Dist.CLIENT)
public final class SmokeScene {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH-Smoke");
    private static boolean started;
    private static int ticks;
    private static int worldNumber;
    private static long drawsBeforeReload;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("vsdh.smoke")) return;
        Minecraft mc = Minecraft.getInstance();
        if (!started && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            startWorld(mc);
        }
        if (mc.level == null || mc.player == null) return;
        ticks++;
        if (ticks == 40) {
            mc.getConnection().sendCommand("gamemode spectator");
            mc.getConnection().sendCommand("tp @s 0 140 0 45 15");
            mc.getConnection().sendCommand("time set noon");
            mc.getConnection().sendCommand("weather clear");
        }
        if (ticks == 800) {
            long draws = ((VulkanRenderEngine) ApiDhIntegration.getInstance().getBackend()).getTotalTerrainDrawCalls();
            LOGGER.info("Smoke world {}: {} terrain draw commands recorded", worldNumber, draws);
            if (draws <= drawsBeforeReload) throw new IllegalStateException("Smoke scene recorded no new DH draws");
            Screenshot.grab(mc.gameDirectory, "vsdh-smoke-world-" + worldNumber + ".png", mc.getMainRenderTarget(),
                    component -> LOGGER.info("Screenshot: {}", component.getString()));
        }
        if (ticks == 850) mc.getWindow().setWindowed(960, 540);
        if (ticks == 900) {
            mc.getConnection().sendCommand("time set midnight");
            mc.getConnection().sendCommand("weather rain");
        }
        if (ticks == 1000) {
            Screenshot.grab(mc.gameDirectory, "vsdh-smoke-resize-rain-" + worldNumber + ".png", mc.getMainRenderTarget(),
                    component -> LOGGER.info("Resize/rain screenshot: {}", component.getString()));
        }
        if (ticks == 1050 && worldNumber == 1) {
            drawsBeforeReload = ((VulkanRenderEngine) ApiDhIntegration.getInstance().getBackend()).getTotalTerrainDrawCalls();
            LOGGER.info("Disconnecting isolated world to exercise GPU cleanup and reinitialization.");
            // Match PauseScreen.onDisconnect(): this signals the integrated
            // server to stop before Minecraft waits for its shutdown.
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            ticks = 0;
            startWorld(mc);
            return;
        }
        if (ticks == 1050 && worldNumber == 2) {
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            LOGGER.info("Smoke scene finished; stopping development client.");
            mc.stop();
        }
    }
    private static void startWorld(Minecraft mc) {
            started = true;
            worldNumber++;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(4);
            mc.options.framerateLimit().set(60);
            String name = "VSDH-Smoke-" + System.currentTimeMillis();
            LOGGER.info("Creating isolated smoke-test world {}", name);
            mc.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(825765032L, false, false),
                    WorldPresets::createNormalWorldDimensions, mc.screen);
    }

}
