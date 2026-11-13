package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.ShadowCullState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShadowCullerTest {
    private static final float RENDER_DISTANCE = 256;

    /** GL clip depth, camera at the origin looking along {@code direction}. */
    private static Matrix4f clip(Vector3f direction, float far, boolean flipY) {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, far);
        if (flipY) projection.m01(-projection.m01()).m11(-projection.m11()).m21(-projection.m21()).m31(-projection.m31());
        var up = Math.abs(direction.y) > 0.99f ? new Vector3f(0, 0, -1) : new Vector3f(0, 1, 0);
        return projection.mul(new Matrix4f().lookAlong(direction, up));
    }

    private static boolean box(ShadowCuller culler, float x, float y, float z) {
        return culler.visible(x, y, z, x + 16, y + 16, z + 16);
    }

    private static ShadowCuller swept(Matrix4f clip, Vector3f light) {
        return ShadowCuller.create(ShadowCullState.ADVANCED, false, 160, -1, 0, RENDER_DISTANCE, clip, light);
    }

    @Test void distanceStateAndVoxelisingDefaultKeepOnlyTheIrisCube() {
        var clip = clip(new Vector3f(0, 0, -1), 1024, false);
        var light = new Vector3f(0, 1, 0);
        var distance = ShadowCuller.create(ShadowCullState.DISTANCE, false, 64, 1, 0, RENDER_DISTANCE, clip, light);
        assertEquals(ShadowCuller.Shape.DISTANCE, distance.shape());
        assertTrue(box(distance, 40, 0, 40), "behind the camera but inside the cube");
        assertFalse(box(distance, 80, 0, 0), "outside the 64 block cube");
        var voxel = ShadowCuller.create(ShadowCullState.DEFAULT, true, 64, 1, 0, RENDER_DISTANCE, clip, light);
        assertEquals(ShadowCuller.Shape.DISTANCE, voxel.shape());
        assertEquals(ShadowCuller.Shape.NONE,
                ShadowCuller.create(ShadowCullState.DISTANCE, false, 192, 2, 0, RENDER_DISTANCE, clip, light).shape(),
                "a cube past the render distance culls nothing");
        assertEquals(ShadowCuller.Shape.NONE,
                ShadowCuller.create(ShadowCullState.DISTANCE, false, 192, -1, 0, RENDER_DISTANCE, clip, light).shape(),
                "an undeclared multiplier culls nothing for the distance state");
    }

    @Test void noonSweepKeepsCastersAboveTheViewAndDropsThoseBehind() {
        var culler = swept(clip(new Vector3f(0, 0, -1), 1024, true), new Vector3f(0, 1, 0));
        assertEquals(ShadowCuller.Shape.SWEPT, culler.shape());
        assertEquals(Float.POSITIVE_INFINITY, culler.limit());
        assertTrue(box(culler, -8, -40, -80), "seen terrain");
        assertTrue(box(culler, -8, 180, -80), "high above seen terrain");
        assertFalse(box(culler, -8, -40, 64), "behind the camera");
        assertFalse(box(culler, -8, 180, 64), "above terrain behind the camera");
        assertTrue(box(culler, -8, -8, -8), "the section holding the camera");
    }

    @Test void lowSunKeepsOffscreenCastersOnTheLitSideOnly() {
        var culler = swept(clip(new Vector3f(0, 0, -1), 1024, false), new Vector3f(1, 0.05f, 0));
        assertTrue(box(culler, 140, -8, -60), "off screen towards the sun");
        assertFalse(box(culler, -160, -8, -60), "off screen away from the sun");
    }

    @Test void safeZoneKeepsTheNearCubeAndHonoursTheDistanceCube() {
        var clip = clip(new Vector3f(0, 0, -1), 1024, false);
        var culler = ShadowCuller.create(ShadowCullState.SAFE_ZONE, false, 192, -1, 32, RENDER_DISTANCE, clip,
                new Vector3f(0, 1, 0));
        assertEquals(ShadowCuller.Shape.SAFE_ZONE, culler.shape());
        assertEquals(192, culler.limit(), "an undeclared multiplier counts as one for the safe zone");
        assertEquals(32, culler.keep());
        assertTrue(box(culler, -8, -8, 20), "behind the camera inside the safe cube");
        assertFalse(box(culler, -8, -8, 80), "behind the camera outside the safe cube");
        assertTrue(box(culler, -8, -40, -150), "seen and inside the distance cube");
        assertFalse(box(culler, -8, -40, -220), "seen but past the distance cube");
        var noVoxels = ShadowCuller.create(ShadowCullState.SAFE_ZONE, false, 192, 1, 0, RENDER_DISTANCE, clip,
                new Vector3f(0, 1, 0));
        assertTrue(box(noVoxels, -8, -8, -8), "a zero safe cube still keeps the camera's own section");
        assertFalse(box(noVoxels, -8, -8, 20), "but nothing else behind the camera");
    }

    @Test void boundedSweepAndInfiniteFarPlane() {
        var bounded = ShadowCuller.create(ShadowCullState.DEFAULT, false, 96, 1, 0, RENDER_DISTANCE,
                clip(new Vector3f(0, 0, -1), 1024, false), new Vector3f(0, 1, 0));
        assertEquals(96, bounded.limit());
        assertFalse(box(bounded, -8, -8, -130));
        var infinite = new Matrix4f().setPerspective((float) Math.toRadians(70), 16 / 9f, 0.05f,
                Float.POSITIVE_INFINITY, false).mul(new Matrix4f().lookAlong(0, 0, -1, 0, 1, 0));
        var culler = swept(infinite, new Vector3f(0, 1, 0));
        assertTrue(box(culler, -8, -40, -200));
        assertFalse(box(culler, -8, -40, 64));
    }

    @Test void invalidInputsAreRejected() {
        var clip = clip(new Vector3f(0, 0, -1), 1024, false);
        assertThrows(IllegalArgumentException.class, () -> swept(clip, new Vector3f()));
        assertThrows(IllegalArgumentException.class, () -> ShadowCuller.create(ShadowCullState.ADVANCED, false,
                Float.NaN, 1, 0, RENDER_DISTANCE, clip, new Vector3f(0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> ShadowCuller.create(null, false, 160, 1, 0,
                RENDER_DISTANCE, clip, new Vector3f(0, 1, 0)));
    }

    /** Areas are rejected before their sections, which is only exact for a monotone test. */
    @Test void containingBoxesOfVisibleBoxesAreVisible() {
        var random = new Random(0x5EED);
        for (int scene = 0; scene < 200; scene++) {
            var culler = randomCuller(random);
            for (int i = 0; i < 200; i++) {
                float x = random.nextFloat() * 512 - 256, y = random.nextFloat() * 512 - 256, z = random.nextFloat() * 512 - 256;
                float w = 1 + random.nextFloat() * 128;
                float sx = x + random.nextFloat() * w * 0.5f, sy = y + random.nextFloat() * w * 0.5f, sz = z + random.nextFloat() * w * 0.5f;
                float sw = random.nextFloat() * w * 0.5f;
                if (culler.visible(sx, sy, sz, sx + sw, sy + sw, sz + sw))
                    assertTrue(culler.visible(x, y, z, x + w, y + w, z + w), "scene " + scene + " box " + i);
            }
        }
    }

    /** Any point that lies between the light and a seen point must stay in a kept box. */
    @Test void neverDropsACasterOfSomethingSeen() {
        var random = new Random(0xCA57);
        var point = new Vector4f();
        int checkedCasters = 0;
        for (int scene = 0; scene < 40; scene++) {
            var direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 1.6f - 0.8f, random.nextFloat() * 2 - 1).normalize();
            var light = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            if (light.lengthSquared() < 0.01f) continue;
            light.normalize();
            var clip = clip(direction, 300, random.nextBoolean());
            var culler = swept(clip, light);
            for (int i = 0; i < 300; i++) {
                float x = random.nextFloat() * 400 - 200, y = random.nextFloat() * 400 - 200, z = random.nextFloat() * 400 - 200;
                if (culler.visible(x, y, z, x + 16, y + 16, z + 16)) continue;
                // A culled box: walk away from the light from a grid of its points; none may enter the view.
                for (int s = 0; s < 27; s++) {
                    float px = x + 8 * (s % 3), py = y + 8 * (s / 3 % 3), pz = z + 8 * (s / 9);
                    for (float t = 0; t <= 600; t += 4) {
                        point.set(px - light.x * t, py - light.y * t, pz - light.z * t, 1);
                        clip.transform(point);
                        boolean inside = point.w > 0 && Math.abs(point.x) <= point.w && Math.abs(point.y) <= point.w
                                && Math.abs(point.z) <= point.w;
                        assertFalse(inside, "scene " + scene + " dropped a caster at " + px + "," + py + "," + pz);
                    }
                    checkedCasters++;
                }
            }
        }
        assertTrue(checkedCasters > 5_000, "the sweep must actually reject boxes: " + checkedCasters);
    }

    private static ShadowCuller randomCuller(Random random) {
        var direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 1.6f - 0.8f, random.nextFloat() * 2 - 1).normalize();
        var light = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
        if (light.lengthSquared() < 0.01f) light.set(0, 1, 0);
        var states = ShadowCullState.values();
        return ShadowCuller.create(states[random.nextInt(states.length)], random.nextBoolean(), 32 + random.nextFloat() * 300,
                random.nextBoolean() ? -1 : random.nextFloat() * 2, random.nextFloat() * 64, RENDER_DISTANCE,
                clip(direction, 300, random.nextBoolean()), light);
    }
}
