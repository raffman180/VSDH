# Nächster Schritt nach 0.4: vollständige Welt-Anbindung

Der Nutzer hat den eigenen Reforged-Fork bereits beauftragt. Diese Entscheidung
nicht erneut abfragen. Minecraft 1.21.1 / NeoForge 21.1.264 / DH 3.3.3 bleiben
festgelegt; Complementary Reimagined r5.9.3 MEDIUM ist das erste Pack-Ziel.

Vorhanden und separat geprüft sind MRT/HDR, Shaderübersetzung, native Vollbild-
und Terrainprogramme, Farb-/Tiefen-Rendergraph, persistente Puffer, Mipmaps und
Terrainmaterialien/Normalen/Tangenten. Die normale Vulkan-/DH-Weltdarstellung läuft.
Das ist weiterhin keine vollständige Complementary-Weltdarstellung.

1. WorldRenderer.renderSectionLayer und Frame-Grenzen mit NativeShaderChain
   verbinden. Echte Kamera-/Welt-/Zeit-/Wetter-/Lichtuniforms und Texturen liefern.
   Pack-Alpha-/Blend-/Cull-Zustände beachten; den Shaderpfad erst nach erfolgreicher
   Vorbereitung samt sauberem Rückfall aktivieren.
2. Schattenkamera und passende Schattengeometrie einbauen. Danach Himmel,
   Entitäten, Blockentitäten, Hand, Partikel, Wetter und Transparenz durch die
   jeweiligen Pack-Programme führen. Keine fehlenden Familien verstecken.
3. DH-Terrain/-Wasser durch Pack-Programme und gemeinsame Tiefenkomposition führen.
   Übergänge mit realen Szenen prüfen. Erst danach Pack-/Profilwahl und kontrollierte
   Ressourcenwechsel freigeben.

Zum Abschluss Tag/Nacht/Regen, Wasser/Glas/Laub, Emission/Normalmaps, verschiedene
Dimensionen, Weltwechsel, Resize und Multiplayer prüfen. Weitere Shaderpacks,
DH-Versionen und GPUs erhalten erst nach eigenen Prüfungen eine Kompatibilitätsaussage.
Keine FPS-Zusagen ohne Messung und keine absolute Fehlerfreiheitszusage.

Einstiegspunkte: TERRAIN_FORMAT.md, RENDER_GRAPH.md, HANDOFF.md und
MIGRATION_STATUS.md. Shader-ZIP und private Referenzquellen nie mitliefern.
