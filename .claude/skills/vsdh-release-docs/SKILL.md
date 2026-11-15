---
name: vsdh-release-docs
description: Prepare VSDH release metadata - CHANGELOG.md entries, the CurseForge/Modrinth page (CURSEFORGE_PAGE.md, docs/curseforge/DESCRIPTION.md), dist/<version>/ with SHA256SUMS. Use when finishing a change set, cutting a beta, or writing store text. Uploading is always the user's action.
---

# VSDH release docs

## Changelog
- `CHANGELOG.md` uses Keep a Changelog style with an `## Unreleased` section on top and real
  version numbers (currently `0.27.0-beta`). Add each user-visible change under Unreleased
  (Added / Changed / Fixed / diagnostics). Never invent a `1.0.0` or bump the version unless
  the user decides on a release.
- Write what a player notices, then the switch to turn it off (`-Dvsdh.x=false`) if there is one.

## Store page
- `docs/curseforge/DESCRIPTION.md` is the source; `CURSEFORGE_PAGE.md` in the root is the
  copy-paste version in English. Keep them in sync.
- Only claim what was tested in game, with which versions (Minecraft 1.21.1, NeoForge
  21.1.264, DH 3.2.0-b / 3.3.0–3.3.3). Shader packs: name only packs that were checked and
  say "tested with", never "supports all". No FPS claims over Iris without a same-scene
  comparison from `docs/PERFORMANCE.md`, and then with the conditions (resolution, window mode).
- Credit VulkanMod / VulkanMod Reforged, Distant Horizons port reference and licenses
  (see NOTICE, COPYING). Never bundle shader packs; link them.
- AI assistance disclosure if the platform asks for it.

## dist
- `dist/<version>/` holds the JAR(s) and `SHA256SUMS.txt`; compute hashes with
  `sha256sum` (or `Get-FileHash -Algorithm SHA256`) from the exact files that passed the
  smoke tests. Keep `6699fe6` / `dist/0.1` as the baseline; never delete older dist folders.

## Publishing
Uploading to CurseForge/Modrinth and creating GitHub releases is the user's action unless
they explicitly ask for it in the current conversation. Pushing commits to the existing
branch is allowed (user memory).
