package net.vulkanmod.render.chunk;

import net.vulkanmod.render.vertex.TerrainRenderType;

import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Whether a terrain layer can issue any draw this frame. Vanilla asks for all five layers, but the renderer
 * merges them into two or three ({@link TerrainRenderType#COMPACT_RENDER_TYPES}), so SOLID and TRIPWIRE (and
 * CUTOUT with one opaque layer) never hold geometry. A shader-pack frame used to prepare such a layer all the
 * same: uniforms, an empty render pass and, where the pack's program samples a colour target it draws into
 * (Eclipse gbuffers_water: colortex7 and colortex11), one full-image snapshot copy per target.
 * {@code -Dvsdh.skipEmptyTerrainLayers=false} restores that.
 */
public final class TerrainLayerDraws {
    private TerrainLayerDraws() {}

    public static final boolean SKIP_EMPTY = enabled(System.getProperty("vsdh.skipEmptyTerrainLayers"));

    static boolean enabled(String property) { return !"false".equalsIgnoreCase(property); }

    /** The layers that receive section geometry, exactly the set the draw walk accepts. */
    public static Set<TerrainRenderType> drawn(boolean uniqueOpaqueLayer) {
        return uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
    }

    /**
     * @param layerHoldsGeometry the layer is one of {@link #drawn}; areas are not asked otherwise
     * @param areaHasGeometry    for area index i: the area has an uploaded buffer of this layer and at least one
     *                           queued section, i.e. the condition under which the draw walk binds and draws it
     * @return false only if the draw walk would not issue a single draw
     */
    public static boolean mayDraw(boolean layerHoldsGeometry, int areaCount, IntPredicate areaHasGeometry) {
        if (!layerHoldsGeometry) return false;
        for (int i = 0; i < areaCount; i++) if (areaHasGeometry.test(i)) return true;
        return false;
    }
}
