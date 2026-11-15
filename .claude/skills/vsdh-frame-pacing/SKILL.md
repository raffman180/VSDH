---
name: vsdh-frame-pacing
description: Investigate stutter, hitches, camera-turn lag, ghosting or low FPS in VSDH (Vulkan renderer + shader packs + Distant Horizons) the measured way. Use for any "unsmooth", "ruckelt", "zieht nach", "lag when turning", FPS or 1%-low complaint before changing code.
---

# VSDH frame pacing and turn smoothness

Profile before optimising. The repository has a long history of measured dead ends; read
the end of `docs/PERFORMANCE.md` and the last entries of `docs/NEXT_AI_PROMPT.md` first,
and do not repeat an experiment listed there as rejected (GPU gap between frames, Mailbox,
present thread, DH upload budget, fence polling, swapchain image count, -Xms=-Xmx, G1 tuning).

## 1. Classify the symptom first

"Something drags behind when I turn" can be four different bugs. Name which one you are
chasing, and ask the user (or read their report) for pack, window mode, DH on/off:

| Symptom | Likely layer | First check |
|---|---|---|
| Image smears/ghosts behind moving edges, worse with a pack | temporal reprojection (TAA, motion blur, SSR history) | `gbufferPrevious*`, `previousCameraPosition` vs Iris with `zzUniformProbe.zip`; bobbing must be in modelview for current AND previous |
| Terrain/water appears late at the screen edge after turning | section visibility/build | `SectionGraph.prebuildHidden`, `openUncompiled`, `ChunkCatchUp`; walk/turn series |
| Single long frames while turning | CPU spike (visibility rebuild, uploads, allocation, tick) | frame CSV + JFR, `-Dvsdh.chunkDiag=true`, `-Dvsdh.spikeMs` |
| Evenly low FPS or periodic 15–30 ms frames | GPU or present | `-Dvsdh.gpuProfile=true`, `GPU_IDLE`, DH loading (see PERFORMANCE.md) |

## 2. Offline work (always allowed)

- Read the code path, write a unit test that reproduces a math or ordering bug
  (projection, previous-frame matrices, culling monotonicity), fix, `vsdh-build-check`.
- Add diagnostics behind a `-Dvsdh.*` switch that is off by default; allocation-free on the
  render thread (no per-draw maps, regex, streams, boxing).
- Every behaviour change gets an A/B switch (`-Dvsdh.<feature>=false`) and an AGENTS.md line.

## 3. In-game measurement (only after the user's fresh go)

The user games on this PC. Never start a test client, bench or release matrix without an
explicit go in the current conversation turn. Before every run check that no `javaw.exe`
of PrismLauncher and no other game (cs2, RocketLeague, …) runs; if one starts, stop the
client and discard the result. One client at a time, with a watchdog (log silent > 6 min → stop).

Tools (all under `build/reference/`, never shipped):
- `run-vsdh-026-flight.ps1 -Packs <Pack> -Tag <tag> [-HighResolution] [-SkipIris] [-Extra '-PtestSysProps=a=b,c=d']`
  → `bench-026/flight-summary.log` and per-frame CSVs; `python flight-analyse.py '<glob>'`
  gives >1/>2/>3 refresh-interval frames per second at 165 Hz. Single runs vary 168–227 FPS:
  compare medians of several runs, and check "DH land draws" plus dhOpaque/dhWater so DH
  loaded the same amount of terrain.
- Turn/walk series: `-PtestSysProps=vsdh.turnSeries=true,vsdh.turnSeries.walk=true` (spectator)
  or `vsdh.turnSeries.ground=true` (real walking, needed for bobbing bugs). Screenshot diffs mix
  bob phases: confirm flicker per frame, not by one diff.
- GPU per pass: `run-vsdh-026-profile.ps1` vs `run-iris-gpu-profile.ps1` (same seed, camera, DH config).
- JFR: `-PtestJfr=<file>`; render-thread shares in big packs are rough (100–300 samples).

Never write DH config from code; the harness scripts reset `rendererMode`.

## 4. Report

Separate: code review, compiled, unit tests, in-game measured (with run count and medians).
Do not claim a smoothness or FPS gain without a same-scene A/B; "not measured in game" is a
valid and required statement. Update `docs/PERFORMANCE.md`, `docs/NEXT_AI_PROMPT.md`,
`docs/HANDOFF.md` and AGENTS.md with what was found, including rejected ideas.
