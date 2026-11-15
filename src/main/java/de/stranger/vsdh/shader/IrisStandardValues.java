package de.stranger.vsdh.shader;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.Val;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.gl.GlTexture;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The remaining standard values Iris 1.8 registers for every pack on Minecraft 1.21.1: player
 * state, world info, biome climate, real time and fog state. Formulas follow Iris (LGPL-3.0, see
 * NOTICE). Iris's HardcodedCustomUniforms (timeAngle, day, rainStrengthS, ...) are never
 * registered there; packs define those names as their own formulas, so they stay out of here.
 * <p>
 * Captured once per world frame on the render thread. Fog values and {@code gtextureSize} read
 * the live draw state when a program is written, as Iris samples them per draw.
 */
public final class IrisStandardValues {
    /** Implemented by a LevelRenderer accessor: the client tick counter vanilla clouds animate with. */
    public interface LevelTicks { int vsdh$ticks(); }

    public static final Map<String, UniformShape> SHAPES;
    static {
        var shapes = new LinkedHashMap<String, UniformShape>();
        for (String name : new String[]{"pi", "playerMood", "constantMood", "currentPlayerHunger", "maxPlayerHunger",
                "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir", "maxPlayerAir", "cloudTime", "ambientLight",
                "rainfall", "temperature", "fogStart", "fogEnd", "fogDensity"})
            shapes.put(name, UniformShape.FLOAT);
        for (String name : new String[]{"currentRenderedItemId", "currentSelectedBlockId", "logicalHeightLimit",
                "biome_category", "currentColorSpace", "fogMode", "fogShape"})
            shapes.put(name, UniformShape.INT);
        for (String name : new String[]{"hideGUI", "is_sneaking", "is_sprinting", "is_invisible", "is_burning",
                "is_on_ground", "firstPersonCamera", "isSpectator", "hasCeiling", "hasSkylight"})
            shapes.put(name, UniformShape.BOOL);
        shapes.put("playerBodyVector", UniformShape.VEC3);
        shapes.put("currentSelectedBlockPos", UniformShape.VEC3);
        shapes.put("entityColor", UniformShape.VEC4);
        shapes.put("currentDate", UniformShape.IVEC3);
        shapes.put("currentTime", UniformShape.IVEC3);
        shapes.put("currentYearTime", UniformShape.IVEC2);
        shapes.put("gtextureSize", UniformShape.IVEC2);
        shapes.put("blendFunc", UniformShape.IVEC4);
        SHAPES = Map.copyOf(shapes);
    }
    public static final java.util.Set<String> NAMES = SHAPES.keySet();
    private static final int GL_LINEAR = 9729, GL_EXP2 = 2049;

    private final Map<String, Float> floats = new LinkedHashMap<>();
    private final Map<String, Boolean> flags = new LinkedHashMap<>();
    private final int[] integer = new int[1], date = new int[3], clock = new int[3], yearTime = new int[2], pair = new int[2];
    private final float[] body = new float[3], selected = new float[3], one = new float[1];
    private int selectedBlockId, logicalHeight = 256, biomeCategory;
    private float waterDensity;
    private ToIntFunction<BlockState> materials = state -> 0;


    /** The block-state IDs of the installed pack, for {@code currentSelectedBlockId}. */
    public void materials(ToIntFunction<BlockState> materials) { this.materials = materials; }

    public void capture(Vec3 camera, float partialTick, float frameTime, float rain, float blindness, float sunAngle,
                        float waterFogDensity) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        var entity = mc.getCameraEntity();
        waterDensity = waterFogDensity;
        floats.put("pi", (float) Math.PI);
        flags.put("hideGUI", mc.options.hideGui);
        flags.put("is_sneaking", player != null && player.isCrouching());
        flags.put("is_sprinting", player != null && player.isSprinting());
        flags.put("is_invisible", player != null && player.isInvisible());
        flags.put("is_burning", player != null && player.isOnFire());
        flags.put("is_on_ground", player != null && player.onGround());
        float mood = entity instanceof LocalPlayer local ? Math.clamp(local.getCurrentMood(), 0, 1) : 0;
        floats.put("playerMood", mood);
        // Iris keeps a never-reset mood through its own mixin; vanilla only exposes the resetting one.
        floats.put("constantMood", mood);
        boolean survival = player != null && mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival();
        floats.put("currentPlayerHunger", survival ? player.getFoodData().getFoodLevel() / 20f : -1);
        floats.put("maxPlayerHunger", 20f);
        floats.put("currentPlayerArmor", survival ? player.getArmorValue() / 50f : -1);
        floats.put("maxPlayerArmor", 50f);
        floats.put("currentPlayerAir", survival ? (float) player.getAirSupply() / player.getMaxAirSupply() : -1);
        floats.put("maxPlayerAir", survival ? (float) player.getMaxAirSupply() : -1f);
        flags.put("firstPersonCamera", switch (mc.options.getCameraType()) {
            case THIRD_PERSON_BACK, THIRD_PERSON_FRONT -> false;
            default -> true;
        });
        flags.put("isSpectator", mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR);
        captureSelection(mc, camera);
        int ticks = mc.levelRenderer instanceof LevelTicks access ? access.vsdh$ticks() : 0;
        floats.put("cloudTime", (ticks + partialTick) * 0.03f);
        if (entity != null) {
            var forward = entity.getForward();
            body[0] = (float) forward.x; body[1] = (float) forward.y; body[2] = (float) forward.z;
        }
        var dimension = level == null ? null : level.dimensionType();
        logicalHeight = dimension == null ? 256 : dimension.logicalHeight();
        flags.put("hasCeiling", dimension != null && dimension.hasCeiling());
        flags.put("hasSkylight", dimension == null || dimension.hasSkyLight());
        floats.put("ambientLight", dimension == null ? 0 : dimension.ambientLight());
        captureTime();

        Holder<Biome> biome = level != null && player != null ? level.getBiome(player.blockPosition()) : null;
        biomeCategory = biome == null ? 0 : category(biome);
        floats.put("rainfall", biome == null ? 0 : biome.value().getModifiedClimateSettings().downfall());
        floats.put("temperature", biome == null ? 0 : biome.value().getBaseTemperature());
    }

    private void captureSelection(Minecraft mc, Vec3 camera) {
        selectedBlockId = 0;
        selected[0] = selected[1] = selected[2] = -256;
        HitResult hit = mc.hitResult;
        if (mc.level == null || !(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir() && mc.level.getWorldBorder().isWithinBounds(pos)) selectedBlockId = materials.applyAsInt(state);
        var center = pos.getCenter().subtract(camera);
        selected[0] = (float) center.x; selected[1] = (float) center.y; selected[2] = (float) center.z;
    }

    private void captureTime() {
        var now = LocalDateTime.now();
        date[0] = now.getYear(); date[1] = now.getMonthValue(); date[2] = now.getDayOfMonth();
        clock[0] = now.getHour(); clock[1] = now.getMinute(); clock[2] = now.getSecond();
        int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
        yearTime[0] = elapsed;
        yearTime[1] = now.toLocalDate().lengthOfYear() * 86400 - elapsed;
    }

    /** Iris's tag-based category, in the order of its BiomeCategories enum. */
    private static int category(Holder<Biome> biome) {
        if (biome.is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) return 0;
        if (biome.is(BiomeTags.HAS_VILLAGE_SNOWY)) return 7;
        if (biome.is(BiomeTags.IS_HILL)) return 2;
        if (biome.is(BiomeTags.IS_TAIGA)) return 1;
        if (biome.is(BiomeTags.IS_OCEAN)) return 11;
        if (biome.is(BiomeTags.IS_JUNGLE)) return 3;
        if (biome.is(BiomeTags.IS_FOREST)) return 10;
        if (biome.is(BiomeTags.IS_BADLANDS)) return 4;
        if (biome.is(BiomeTags.IS_NETHER)) return 16;
        if (biome.is(BiomeTags.IS_END)) return 8;
        if (biome.is(BiomeTags.IS_BEACH)) return 9;
        if (biome.is(BiomeTags.HAS_DESERT_PYRAMID)) return 12;
        if (biome.is(BiomeTags.IS_RIVER)) return 13;
        if (biome.is(BiomeTags.HAS_CLOSER_WATER_FOG)) return 14;
        if (biome.is(BiomeTags.PLAYS_UNDERWATER_MUSIC)) return 18;
        if (biome.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) return 15;
        if (biome.is(BiomeTags.IS_MOUNTAIN)) return 17;
        return 5;
    }

    /** Pack-expression input; fog is read here at frame time as a pack formula would see it. */
    public boolean read(String name, Val out) {
        if (!NAMES.contains(name)) return false;
        switch (SHAPES.get(name)) {
            case FLOAT -> out.set(scalar(name));
            case INT -> out.set(intValue(name, false));
            case BOOL -> out.set(flags.getOrDefault(name, false));
            case VEC3 -> {
                float[] v = name.equals("playerBodyVector") ? body : selected;
                out.set(v[0], v[1], v[2]);
            }
            case VEC4 -> out.set(0f, 0f, 0f, 0f);
            case IVEC3 -> {
                int[] v = name.equals("currentDate") ? date : clock;
                out.set(v[0], v[1], v[2]);
            }
            case IVEC2 -> {
                int[] v = vec2(name);
                out.set(v[0], v[1]);
            }
            case IVEC4 -> out.set(0, 0, 0, 0);
            default -> throw new IllegalStateException("Unhandled standard value shape " + name);
        }
        return true;
    }

    public boolean write(Std140Layout.Member member, Std140Layout layout, ByteBuffer destination, boolean fullscreen) {
        String name = member.name();
        UniformShape shape = SHAPES.get(name);
        if (shape == null) return false;
        switch (shape) {
            case FLOAT -> { one[0] = scalar(name); layout.putFloats(destination, name, 0, one); }
            case INT -> { integer[0] = intValue(name, fullscreen); layout.putInts(destination, name, 0, integer); }
            case BOOL -> { integer[0] = flags.getOrDefault(name, false) ? 1 : 0; layout.putInts(destination, name, 0, integer); }
            case VEC3 -> layout.putFloats(destination, name, 0, name.equals("playerBodyVector") ? body : selected);
            case VEC4 -> layout.putFloats(destination, name, 0, 0f, 0f, 0f, 0f);
            case IVEC3 -> layout.putInts(destination, name, 0, name.equals("currentDate") ? date : clock);
            case IVEC2 -> layout.putInts(destination, name, 0, vec2(name));
            // Pack passes set blending per program; reported as Iris reports a draw without blending.
            case IVEC4 -> layout.putInts(destination, name, 0, 0, 0, 0, 0);
            default -> throw new IllegalStateException("Unhandled standard value shape " + name);
        }
        return true;
    }

    private float scalar(String name) {
        return switch (name) {
            case "fogStart" -> RenderSystem.getShaderFogStart();
            case "fogEnd" -> RenderSystem.getShaderFogEnd();
            case "fogDensity" -> Math.max(0, waterDensity);
            default -> floats.getOrDefault(name, 0f);
        };
    }

    private int intValue(String name, boolean fullscreen) {
        return switch (name) {
            case "currentRenderedItemId" -> -1;
            case "currentSelectedBlockId" -> selectedBlockId;
            case "logicalHeightLimit" -> logicalHeight;
            case "biome_category" -> biomeCategory;
            case "currentColorSpace" -> 0;
            // Iris builds composite programs with fog off and gbuffers with the vanilla fog state.
            case "fogMode" -> fullscreen ? 0 : waterDensity > 0 ? GL_EXP2 : GL_LINEAR;
            case "fogShape" -> fullscreen ? -1 : RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0;
            default -> throw new IllegalStateException("Unknown integer standard value " + name);
        };
    }

    private int[] vec2(String name) {
        if (name.equals("currentYearTime")) return yearTime;
        var texture = GlTexture.getTexture(RenderSystem.getShaderTexture(0));
        var image = texture == null ? null : texture.getVulkanImage();
        pair[0] = image == null ? 0 : image.width;
        pair[1] = image == null ? 0 : image.height;
        return pair;
    }
}
