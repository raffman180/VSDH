package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** GPU readback only in the development source set; production never waits for these pixels. */
final class BuiltinWorldProbe {
    private static final Set<String> completed = new HashSet<>();

    static void requireComplete() {
        if (!completed.equals(Set.of("air", "water-negative", "effects")))
            throw new AssertionError("Incomplete live uniform probes: " + completed);
    }

    static void verify(WorldShaderSession session, int ticks) {
        String phase = ticks >= 250 && ticks <= 450 ? "air"
                : ticks >= 580 && ticks <= 640 ? "water-negative"
                : ticks >= 740 && ticks <= 770 ? "effects" : null;
        if (phase == null || completed.contains(phase)) return;

        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var entity = camera.getEntity();
        var level = mc.level;
        var medium = camera.getFluidInCamera();
        int mediumId = medium == FogType.WATER ? 1 : medium == FogType.LAVA ? 2 : medium == FogType.POWDER_SNOW ? 3 : 0;
        if (phase.equals("water-negative") && mediumId != 1)
            throw new AssertionError("Water probe did not reach actual water: " + medium);
        if (phase.equals("air") && mediumId != 0)
            throw new AssertionError("Baseline probe is not in air: " + medium);
        if (phase.equals("effects") && (!(entity instanceof LivingEntity living)
                || !living.hasEffect(MobEffects.BLINDNESS) || !living.hasEffect(MobEffects.DARKNESS)
                || !living.hasEffect(MobEffects.NIGHT_VISION)))
            throw new AssertionError("Effects probe did not receive all three real effects");

        var image = session.chain().targets().image(1, Side.MAIN);
        ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(image.width * image.height, 8));
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(image, MemoryUtil.memAddress(bytes));
            int sample = -1;
            for (int pixel = 0; pixel < image.width * image.height - 1; pixel++) {
                if ((pixel % image.width & 1) == 0 && pixel % image.width < image.width - 1
                        && normal(bytes, pixel) && normal(bytes, pixel + 1)) {
                    sample = pixel;
                    break;
                }
            }
            if (sample == -1) return; // Wait for real chunks, never substitute a synthetic quad.

            var lightPos = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
            check(session, bytes, sample, 2, level.getBrightness(LightLayer.BLOCK, lightPos) / 15f,
                    level.getBrightness(LightLayer.SKY, lightPos) / 15f, mediumId / 3f, 1);
            int atlasId = mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
            var atlas = GlTexture.getTexture(atlasId).getVulkanImage();
            float skyAngle = level.getTimeOfDay(0);
            check(session, bytes, sample, 3, atlas.width / 4096f, atlas.height / 4096f, mc.options.gamma().get().floatValue(), 1);

            float night = 0, blind = 0, dark = 0, density = 0;
            if (entity instanceof LivingEntity living) {
                var blindness = living.getEffect(MobEffects.BLINDNESS);
                if (blindness != null) blind = blindness.isInfiniteDuration() ? 1 : Math.min(1, blindness.getDuration() / 20f);
                var darkness = living.getEffect(MobEffects.DARKNESS);
                if (darkness != null) dark = darkness.getBlendFactor(living, 0);
                if (living.hasEffect(MobEffects.NIGHT_VISION)) night = GameRenderer.getNightVisionScale(living, 0);
            }
            if (mediumId == 1) {
                density = .05f;
                if (entity instanceof LocalPlayer player) {
                    density -= player.getWaterVision() * player.getWaterVision() * .03f;
                    if (level.getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) density += .005f;
                }
            }
            check(session, bytes, sample, 4, night, blind, dark, 1);
            var pos = camera.getPosition();
            check(session, bytes, sample, 5, (float) (pos.x - Math.floor(pos.x)), (float) (pos.y - Math.floor(pos.y)),
                    (float) (pos.z - Math.floor(pos.z)), 1);
            var eye = entity.getEyePosition(0);
            check(session, bytes, sample + 1, 5, (float) (pos.x - eye.x) * .01f + .5f,
                    (float) (pos.y - eye.y) * .01f + .5f, (float) (pos.z - eye.z) * .01f + .5f, 1);
            check(session, bytes, sample, 6, level.effects().getCloudHeight() / 1024f,
                    skyAngle < .75f ? skyAngle + .25f : skyAngle - .75f, level.getMoonPhase() / 7f, 1);
            check(session, bytes, sample + 1, 6, density, (float) Math.floor(pos.y) / 512f,
                    (float) (Math.floor(pos.x) - Math.floor(pos.z)) / 512f, 1);
            completed.add(phase);
            LoggerFactory.getLogger("VSDH-Smoke").info("BUILTIN WORLD GPU PASS: phase={}, pixel={}, camera={}, medium={}, night={}, blindness={}, darkness={}, fogDensity={}",
                    phase, sample, pos, medium, night, blind, dark, density);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static float half(ByteBuffer buffer, int offset) {
        return NativePassGpuCheck.halfFloat(buffer.getShort(offset));
    }

    private static boolean normal(ByteBuffer bytes, int pixel) {
        float x = half(bytes, pixel * 8) * 2 - 1;
        float y = half(bytes, pixel * 8 + 2) * 2 - 1;
        float z = half(bytes, pixel * 8 + 4) * 2 - 1;
        return half(bytes, pixel * 8 + 6) > .5f && Math.abs(x * x + y * y + z * z - 1) < .03f;
    }

    private static void check(WorldShaderSession session, ByteBuffer bytes, int pixel, int target, float... expected) {
        var image = session.chain().targets().image(target, Side.MAIN);
        ImageUtil.downloadTextureRegion(image, MemoryUtil.memAddress(bytes), pixel % image.width, pixel / image.width,
                1, 1, image.aspect, image.formatSize);
        for (int channel = 0; channel < 4; channel++) {
            float actual = half(bytes, channel * 2);
            if (!Float.isFinite(actual) || Math.abs(actual - expected[channel]) > .0015f)
                throw new AssertionError("World builtin target " + target + " channel " + channel
                        + ": expected " + expected[channel] + ", actual " + actual);
        }
    }
}
