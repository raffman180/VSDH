# VSDH

User target: Minecraft 1.21.1, NeoForge 21.1.264, Java 21,
VulkanMod Reforged. First milestone: native DH rendering. First shader target:
Complementary Reimagined. Broad future compatibility is a goal, not a claim.

The sole port reference is Braffolk/distant-horizons-vulkanmod commit
a72ba645188c5fab5f9d48254b6703b659543f0c. Renderer reference:
TrulyRin/VulkanMod-Reforged commit 0618965fbedb71ca2ee2e5d4528fcedc4fb27551.
Do not silently substitute Fabric VulkanMod APIs for Reforged APIs. Preserve
upstream credits and license headers. No decompiled references in deliverables.

Pin and validate dependency versions. Keep render code on the physical client.
All Vulkan resource operations belong on the render thread; worker threads may
only stage copied buffers and enqueue work. Preserve world data and user settings.
Do not advertise untested version combinations or unfinished shader support.
Report code review, compilation, unit tests and in-game rendering separately.
Keep docs/MIGRATION_STATUS.md and docs/HANDOFF.md accurate.

User on 2026-11-08 instructed continuing here to completion after the fork
recommendation; use the recommended Reforged fork. Renderer sources are now
part of this project; do not also install the original Reforged JAR. Keep
6699fe6 / dist/0.1 artifacts as the baseline. Complementary test reference:
r5.9.3, SHA-1 838139b54cddb56b2e83cd260d8efd960ac536d6; never bundle the pack.
