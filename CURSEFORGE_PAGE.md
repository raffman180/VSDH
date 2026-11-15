# VSDH — Vulkan, Shader Packs and Distant Horizons, Together

**Beta for NeoForge 1.21.1.** Vulkan speed, your favourite shader packs and Distant Horizons' endless view
distance usually exclude each other. VSDH puts all three into one renderer.

VSDH replaces Minecraft's OpenGL renderer with Vulkan (based on VulkanMod Reforged), loads OptiFine/Iris-style
shader packs natively on top of it, and draws Distant Horizons' far terrain with the pack's own programs, so the
horizon gets the same light, fog and water as the land at your feet.

---

## Why VSDH?

- **One renderer instead of three mods fighting each other.** No Sodium/Iris/VulkanMod juggling: VSDH is the
  renderer and the shader loader.
- **Shader packs that look like they should.** Packs are checked against Iris in the same scene, uniform by uniform.
- **Distant Horizons that belongs to the picture.** Packs with DH support render the far land and water with their
  own DH programs, in Vulkan.
- **Made for long sessions.** Frame pacing, garbage-collection pauses and chunk loading were measured flight by
  flight, not guessed.

## Features

- **Vulkan renderer** based on VulkanMod Reforged, with its own chunk renderer, culling and settings pages.
- **Shader packs** from your `shaderpacks` folder, picked under *Video Settings → Shaders*.
  - Profiles and every per-pack option, read from the pack's own screens and language file.
  - Switching applies at once, also inside a world (the terrain is rebuilt once). Recently used packs are kept
    prepared, so switching back is instant.
  - If a pack's shadow map does not fit your GPU memory, VSDH uses the pack's next smaller shadow resolution instead
    of turning the pack off.
- **Distant Horizons (optional):** LODs are rendered natively in Vulkan; one JAR covers DH 3.2.0-b and 3.3.0–3.3.3.
  Without DH, VSDH runs as Vulkan renderer plus shader loader.
- **Three window modes:** Window, Borderless Fullscreen and Exclusive Fullscreen.
- **Compatibility work for other mods:** GL calls of other mods are emulated on top of Vulkan; calls that are not
  supported yet are reported once in the log instead of failing silently. A title-screen notice names installed
  mods that are known to cause display errors (nothing is blocked).

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

- Windows 11 with an NVIDIA RTX 4070. Other GPUs and operating systems are not tested yet.
- Shader packs compared with Iris in the same scene: Complementary Reimagined/Unbound, Photon, Eclipse Unstable,
  Bliss, Miniature (matching); Solas, Rethinking Voxels (small colour/haze differences); BSL (its own deferred
  clouds are missing). Other packs may work or may not.
- Large mod packs: one 128-mod pack. Mods that draw with advanced OpenGL features (for example Create's Flywheel
  backend) fall back to their simpler rendering.

## Tips for smooth gameplay

- Set *Max Framerate* slightly below your monitor's refresh rate (for example 160 on a 165 Hz screen). In our tests
  this removed the long frames caused by the driver's present queue.
- Java garbage collector: in our 10 GiB shader + DH flights, generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) had
  the fewest long frames; with the default G1, `-XX:-ClassUnloadingWithConcurrentMark` shortened the longest pauses
  from up to 33 ms to about 1 ms.
- With Distant Horizons, a lower DH CPU load / thread count reduces short stalls while new far terrain is generated,
  at the cost of slower LOD loading.
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

This is an unofficial project and not a release of VulkanMod, Distant Horizons or Iris. Shader packs are not
included; download them from their authors. Large parts of the porting and optimisation work were done with the help
of AI coding assistants (Anthropic Claude, OpenAI Codex) and checked with unit tests and in-game measurements.
