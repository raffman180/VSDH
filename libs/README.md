# Festgelegte Build-Abhängigkeiten

Die JARs hier dienen als externe Build- und Laufzeitabhängigkeiten. Sie werden
nicht in die VSDH-JAR aufgenommen. `tools/fetch-dependencies.ps1` lädt sie von
den angegebenen Originalquellen und überprüft ihre Hashes.

| Datei | Originalquelle | Prüfsumme |
|---|---|---|
| flywheel-neoforge-1.21.1-1.0.6.jar | [Offizielles Create-Maven](https://maven.createmod.net/dev/engine-room/flywheel/flywheel-neoforge-1.21.1/1.0.6/) | SHA-256: `31dda15c205eb596d3b3449ef03f6af7363a6cd35b3da4bfe916b304f9e5337e` |
| DistantHorizons-3.3.1-1.21.1-fabric-neoforge.jar | [DH 3.3.1 für 1.21.1](https://modrinth.com/mod/distanthorizons/version/IcOcoekl) | SHA-1: `740532bd920ba59b828caa4eebc3cf90160556c1` |
| DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar | [DH 3.3.3 für 1.21.1](https://modrinth.com/mod/distanthorizons/version/9w34y8ai) | SHA-1: `4357b4d19fab818780c94f0e43dad1cc931634b2` |

DH 3.3.1 dient ausschließlich als Compile-Referenz für den älteren Tiefenadapter.
Der normale Entwicklungsclient lädt DH 3.3.3.
Für DH prüft das Downloadskript zusätzlich den vom Anbieter veröffentlichten
SHA-512-Hash. Flywheel ist ausschließlich eine Compile-Abhängigkeit für vorhandene
Reforged-Kompatibilitätsklassen und wird nicht zum Testprofil hinzugefügt.
Minecraft-/NeoForge-Abhängigkeiten lädt ModDevGradle.

Ab 0.2 enthält VSDH den eigenen Reforged-Fork. Die originale Reforged-JAR darf
deshalb nicht zusätzlich im Spiel installiert sein. Eine alte Kopie in `libs`
wird vom Build nicht mehr verwendet. Vulkan/shaderc/VMA werden wie beim Renderer
als Laufzeitbibliotheken eingebettet; DH und Flywheel bleiben externe Artefakte.
