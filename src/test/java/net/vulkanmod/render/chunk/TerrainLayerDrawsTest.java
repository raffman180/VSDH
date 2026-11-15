package net.vulkanmod.render.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainLayerDrawsTest {
    @Test void mergedLayerNeverDrawsAndItsAreasAreNotAsked() {
        assertFalse(TerrainLayerDraws.mayDraw(false, 3, i -> { throw new AssertionError("asked"); }));
    }

    @Test void drawnLayerNeedsOneAreaWithGeometry() {
        assertFalse(TerrainLayerDraws.mayDraw(true, 0, i -> true));
        assertFalse(TerrainLayerDraws.mayDraw(true, 4, i -> false));
        assertTrue(TerrainLayerDraws.mayDraw(true, 4, i -> i == 3));
        assertTrue(TerrainLayerDraws.mayDraw(true, 1, i -> true));
    }

    @Test void onlyAnExplicitFalseSwitchesItOff() {
        assertTrue(TerrainLayerDraws.enabled(null));
        assertTrue(TerrainLayerDraws.enabled(""));
        assertTrue(TerrainLayerDraws.enabled("true"));
        assertFalse(TerrainLayerDraws.enabled("false"));
        assertFalse(TerrainLayerDraws.enabled("FALSE"));
    }
}
