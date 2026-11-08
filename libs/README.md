# Festgelegte Build-Abhängigkeiten

Die JARs hier dienen als externe Build- und Laufzeitabhängigkeiten. Sie werden
nicht in die VSDH-JAR aufgenommen. `tools/fetch-dependencies.ps1` lädt sie von
den angegebenen Originalquellen und überprüft ihre Hashes.

| Datei | Originalquelle | Prüfsumme |
|---|---|---|
| VulkanMod-Reforged-0.3.5-ALPHA+1.21.1.jar | [Reforged-Release mc-1.21.1-0618965](https://github.com/TrulyRin/VulkanMod-Reforged/releases/tag/mc-1.21.1-0618965) | SHA-256: `61a1c215f4dd591776fc890a7623680ebf409f7ce8c4d083eab0f18b07ec50f1` |
| DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar | [DH 3.3.3 für 1.21.1](https://modrinth.com/mod/distanthorizons/version/9w34y8ai) | SHA-1: `4357b4d19fab818780c94f0e43dad1cc931634b2` |

Für DH prüft das Downloadskript zusätzlich den vom Anbieter veröffentlichten
SHA-512-Hash. Die Reforged-Prüfsumme wurde mit der `SHA256SUMS.txt` des Releases
verglichen. Die Minecraft-/NeoForge-Abhängigkeiten lädt ModDevGradle.
