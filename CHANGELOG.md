# Changelog

## 0.27.0-beta (2026-11-14)

First public beta.

### Added
- Shader packs prepared once in a session are kept (three by default, `-Dvsdh.packCache=N`): switching back to a
  pack with the same file, profile and options no longer waits for its preparation. Opening the Shader page
  prepares up to two previously used packs ahead on a low-priority worker.
- Shader pack changes apply inside a loaded world; the terrain is rebuilt once. `-Dvsdh.liveShaderSwitch=false`
  restores the old behaviour (apply when leaving the world).
- One JAR for Distant Horizons 3.2.0-b and 3.3.0–3.3.3 (DH 3.3.0 is adapted at load time).
- Shadow maps that do not fit GPU memory use the pack's next smaller shadow resolution.
- A configured shader pack whose file was replaced by a newer version uses the newer file.
- Title-screen notice for installed mods that are known to cause display errors.
- Unsupported GL calls of other mods are reported once in the log.

### Changed
- The bundled renderer's mod id is now `vsdh_renderer` (was `vulkanmod`); a separately installed VulkanMod is refused
  with a clear message.
- Frame diagnostics no longer write to the log unless `-Dvsdh.chunkDiag=true` is set.
- Shadow memory budget follows the GPU's memory instead of a fixed 512 MiB.

### Fixed
- Water, reflections and shadows slid along while walking with a shader pack (not while looking around or flying):
  Minecraft puts view bobbing into the projection, and packs rebuild positions from a pure projection. While a pack is
  active the bob is now applied to the model view instead, as Iris does (`-Dvsdh.modelViewBobbing=false` restores
  the old behaviour).
- Distant Horizons terrain and water followed neither view bobbing nor the field-of-view change when starting to
  walk, so the far water slid against the near water while moving. The DH projection is now built from the camera's
  (`-Dvsdh.dhFollowCamera=false` restores the old one).
- After joining a world or switching shader packs the near ground filled in ring by ring over several seconds. The
  visibility walk now passes through sections that are not built yet (`-Dvsdh.openUncompiled=false`), and while many
  visible sections are missing more are built and uploaded per frame (`-Dvsdh.chunkCatchUp=false`).
- Water and shadows that seemed to move with the player (flat, light wedges on the water): chunk sections were
  built only when the camera first looked at them, so for about a second after turning or walking the nearby ground
  was missing and Distant Horizons' far water showed through. Sections the camera has not seen yet are now built
  ahead while nothing visible waits (`-Dvsdh.prebuildHidden=false` turns it off).
- Borderless fullscreen presents through the Vulkan swapchain like exclusive fullscreen (it used the DXGI flip
  path of ordinary windows and had about twice as many long frames). Video Settings > Window Mode: Window,
  Borderless Fullscreen, Exclusive Fullscreen; F11 uses the chosen kind. `-Dvsdh.borderlessPresent=dxgi` restores
  the old path.
- A screenshot taken while the game was minimised in exclusive fullscreen (or right after) could crash the
  graphics driver: the swapchain images were destroyed but still handed out. Texture ids of the swapchain no
  longer leak on every resize.
- Map and minimap mods that upload their own textures (Raffman's Detailed Map) showed blank tiles.
- With ImmediatelyFast, the in-world pause menu showed only its first button.
- Borderless Window: the present path now follows the real window mode.
- Unbounded memory growth of a view cache while a shader pack was active.
- Several crashes on unusual texture uploads; they now leave the texture blank with a warning.

### Performance
- Terrain layers that hold no geometry are no longer prepared in a shader frame: Eclipse lost two full-screen
  snapshot copies per frame (feedback copies 0.19-0.22 ms -> 0.12 ms at 1440p). `-Dvsdh.skipEmptyTerrainLayers=false`
  restores the old path.
- Measured against Iris 1.8.12 + Sodium 0.6.13 on the same 30 s flight (exclusive fullscreen, 1440p, DH in both):
  average FPS Eclipse +5-10 %, Complementary +13-16 %, no shader +14-27 %; the rarest long frames are on par or
  slightly worse (docs/PERFORMANCE.md).
- Biome lookups on the render thread remember the chosen biome cell per position (about a third of the render
  thread's busy time in a 128-mod pack went to repeated lookups). `-Dvsdh.biomeCornerCache=false` turns it off.
- Fewer allocations per frame in the shader host and pipeline state.
- Biome lookups for positions that are not remembered reuse the per-cell part of the distance arithmetic
  (ambient-particle probes, map and tint sweeps). `-Dvsdh.biomeFiddleCache=false` turns it off.
- Animated textures stage only the frame that is uploaded instead of the whole animation sheet each tick.
  `-Dvsdh.tightSpriteUpload=false` restores the old copy.
- Shader uniforms: the provider of each uniform is resolved once per program instead of on every write.
  `-Dvsdh.uniformRoutes=false` restores the full lookup order.
- Shader programs that read a colour target they also draw into (the water programs of Eclipse, for example)
  now sample the attachment directly where the GPU supports `VK_EXT_attachment_feedback_loop_layout`, instead of
  copying the full-screen image before each such pass, but only with `-Dvsdh.feedbackLoops=true`: by default the
  copies stay, because water appeared to move with the camera when the attachment was read live.
  Distant-water programs keep reading the stable opaque Distant Horizons depth: reading the live depth while the
  same pass writes it made distant water flicker (Eclipse). `-Dvsdh.dhDepthFeedbackLoop=true` opts in.
- The shadow pass no longer walks every chunk section each frame; it keeps a list of the drawable ones.
  `-Dvsdh.shadowSectionList=false` restores the full walk.
- The tooltip of the frame-rate limit explains how a cap removes presentation hitches.

## 0.26.0-dev

Development builds; see `docs/MIGRATION_STATUS.md`.
