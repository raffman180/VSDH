---
name: vsdh-build-check
description: Build, unit-test and link-check VSDH (NeoForge 1.21.1, Java 21) and fix compile or test failures in a loop. Use after every code change in this repository, when gradlew fails, when a stack trace from the build or a unit test needs reading, or before any commit.
---

# VSDH build check

The project's Definition of Done for code is a clean run of

```bash
./gradlew --offline build verifyDhLinks
```

from the repository root (PowerShell: `.\gradlew.bat --offline build verifyDhLinks`).
`build` compiles the main, `dh330` and test source sets and runs the JUnit tests;
`verifyDhLinks` checks every Distant Horizons class/member VSDH references against the
pinned DH JARs under `build/reference/` (3.2.0-b, 3.3.0–3.3.3).

## Loop

1. Run the command above. Run it in the background when it may exceed two minutes and
   write the output to a log in the scratchpad, not to the repository root.
2. On failure, find the FIRST real error, not the last line:
   - Compile errors: search the log for `error:` and read the file at the reported line.
   - Test failures: open `build/reports/tests/test/index.html` or the XML under
     `build/test-results/test/` for the failing class; the stack trace is in `<failure>`.
   - `verifyDhLinks` failures name the missing DH member and the DH version. Fix the
     reference or the mapping in `src/dh330` (`Dh330Mapping`); never drop a DH version
     from the list to make it pass.
   - Mixin errors only appear at runtime; a green build does not prove a mixin applies.
3. Fix the cause, then rerun the full command. Repeat until it is green.
4. Report separately: compiled, unit tests (count), `verifyDhLinks`, and that nothing was
   started in game (unless it was, with the user's fresh go).

## Rules that bite here

- Never use `--refresh-dependencies` or go online to "fix" a build: versions are pinned
  (NeoForge 21.1.264, Java 21). Offline is the default.
- Keep `/build/` anchored in `.gitignore`: `src/.../render/chunk/build` is source.
- Do not weaken or delete a test to get green. If a test encodes an old assumption that
  AGENTS.md has superseded, change the test and say so in the report.
- Render code stays on the physical client; Vulkan resource calls only on the render thread.
- Core render code must not load `com.seibel` classes unguarded (DH is optional). After
  touching DH-facing code, also build with `-PtestNoDh` where the change matters.
- Commit only after a green run (see the user's memory: commit and push finished work on
  `codex/renderer-shaders`, never logs, packs, worlds or `build/`).
