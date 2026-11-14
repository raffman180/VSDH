# VSDH — Vulkan renderer, shader packs and Distant Horizons for NeoForge 1.21.1

**Beta.** VSDH replaces Minecraft's OpenGL renderer with Vulkan and puts three things together that normally do not
work at the same time: a fast Vulkan renderer, OptiFine/Iris-style shader packs, and Distant Horizons.

## Features

- **Vulkan renderer** based on VulkanMod Reforged, with its own chunk renderer, culling and settings pages.
- **Shader packs** from your `shaderpacks` folder, picked under *Video Settings → Shaders*.
  - Profiles and per-pack options, read from the pack's own screens and language file.
  - Switching applies at once, also inside a world (the terrain is rebuilt once).
  - If a pack's shadow map does not fit your GPU memory, VSDH uses the pack's next smaller shadow resolution instead
    of turning the pack off.
- **Distant Horizons (optional):** LODs are rendered natively in Vulkan, and shader packs with DH support draw the far
  terrain and water with their own programs. One JAR covers DH 3.2.0-b and 3.3.0–3.3.3.
- **Compatibility work for other mods:** GL calls of other mods are emulated on top of Vulkan; calls that are not
  supported yet are reported once in the log instead of failing silently. A notice on the title screen names
  installed mods that are known to cause display errors (nothing is blocked).

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| GPU | Vulkan 1.2 capable |
| Distant Horizons | optional: 3.2.0-b, 3.3.0, 3.3.1, 3.3.2 or 3.3.3 |

**Do not install together with:** VulkanMod, Sodium, Embeddium, Iris or Oculus. VSDH contains its own renderer and
shader loader; the game refuses to start with a clear message if one of them is present.

## What has been tested

- Windows 11 with an NVIDIA RTX 4070 (other GPUs and operating systems are not tested yet).
- Shader packs compared with Iris in the same scene: Complementary Reimagined/Unbound, Photon, Eclipse Unstable,
  Bliss, Miniature (matching); Solas, Rethinking Voxels (small colour/haze differences); BSL (its own deferred clouds
  are missing). Other packs may work or may not.
- Large mod packs: one 128-mod pack. Mods that draw with advanced OpenGL features (for example Create's Flywheel
  backend) fall back to their simpler rendering.

## Tips for smooth gameplay

- Set *Max Framerate* slightly below your monitor's refresh rate (for example 160 on a 165 Hz screen). In our tests
  this removed the long frames caused by the driver's present queue.
- Java argument `-XX:-ClassUnloadingWithConcurrentMark` shortened Java's longest garbage-collection pauses from up to
  33 ms to about 1 ms in our measurements. Keep the default G1 collector.
- If you use Spark, turn off its `backgroundProfiler` in `config/spark/config.json`; it pauses the game many times per
  second.

## Reporting problems

Please open an issue at https://github.com/raffman180/VSDH/issues with your `logs/latest.log`, the shader pack and
the mod list.

## Credits and license

VSDH is licensed under LGPL-3.0. Source code: https://github.com/raffman180/VSDH

- VulkanMod by xCollateral and contributors, VulkanMod Reforged by Rindw (renderer).
- distant-horizons-vulkanmod by Braffolk and contributors (Distant Horizons Vulkan adapter).
- Vitrail shader code by avpbynf (shader pack reading and GLSL translation), with Iris and StarEval attributions.
- Distant Horizons by James Seibel and contributors (external, not included).
- LWJGL (Vulkan, shaderc, VMA) under their licenses.

This is an unofficial project and not a release of VulkanMod, Distant Horizons or Iris. Large parts of the porting and
optimisation work were done with the help of AI coding assistants (Anthropic Claude, OpenAI Codex) and checked with
unit tests and in-game measurements.
