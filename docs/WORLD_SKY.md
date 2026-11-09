# Pack-Himmel in VSDH 0.11

Minecraft 1.21.1 liefert die Himmelsmeshes selbst. Der Vulkan-Pass verwendet
die bereits hochgeladenen Puffer, die Pack-Ausgabeziele und Mischregeln sowie
den Tiefenzustand des jeweiligen Spielaufrufs.

| Szene | Packprogramm | Minecraft-Format |
|---|---|---|
| Himmel, Sterne, dunkler Horizont | gbuffers_skybasic | POSITION |
| Sonnenauf- und -untergang | gbuffers_skybasic | POSITION_COLOR |
| Sonne und Mond | gbuffers_skytextured | POSITION_TEX |
| End-Himmel | gbuffers_skytextured | POSITION_TEX_COLOR |

WorldShaderSkyMixin begrenzt das Überschreiben des VBO-Passes auf die
konkreten Spielaufrufe. Der Clipraum verwendet wie das Weltterrain eine
einmalige Tiefenumrechnung und eine umgedrehte Y-Zeile. Die Draw-Matrizen
und die Spielfarbe bleiben getrennt von der Kamerahistorie. Die Packoptionen
für Sonne, Mond, Sterne und Himmelskreis gelten in der Shadersitzung.
Ungenutzte SPIR-V-Inputs ändern die Orte der übrigen Attribute nicht.

Der CPU-Audit übersetzt vier Varianten pro Dimension. Ein echter
Complementary-Weltlauf belegt Vulkan-Zeichenaufrufe für Himmelskreis,
Sonne, Mond und Sterne in der Oberwelt. Der Diagnose-Shader prüft
Himmelsfarbe und Phase mit GPU-Rücklesen. End-Himmel und Fremdmods für
Himmelsgeometrie benötigen eigene Spieltests.

## Welttiefe vor der Hand

GameRenderer löscht die Tiefe unmittelbar vor seinem normalen Handpass.
Composite und Final müssen vorher laufen, damit depthtex0 das fertige
Weltbild beschreibt. Die normale Hand und Bildschirmüberlagerungen
folgen danach. Die eigenen Packprogramme für die Hand und die getrennten
Zeitpunkte für depthtex1 und depthtex2 folgen in einem späteren Schritt.
