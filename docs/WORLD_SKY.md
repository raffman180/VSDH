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

## Geschlossener Horizont (11.11.2026)

Vanilla zeichnet nur die obere Himmelsscheibe und die dunkle Scheibe nur,
solange die Kamera unter der Horizonthöhe ist. Blickt man von oberhalb des
Meeresspiegels nach unten, erreicht dort kein `gbuffers_skybasic`-Fragment
das Bild, und die Löschfarbe bleibt stehen. Shaderpack-Loader schließen diese
Lücke mit zusätzlicher Horizontgeometrie. AstraLex und Insanity verlassen
sich darauf: Ihre Himmelsfarbe ist die Farbe, in die ihr Nebel übergeht.
Ohne sie entstand die harte, hellere Chunk-Kante am Sichtweitenrand.

`SkyHorizon` zeichnet direkt vor der Himmelsscheibe, mit deren Matrizen und
Shader und der Nebelfarbe als Shaderfarbe (wie andere Loader), eine achteckige Wand im Abstand der Sichtweite zwischen y=-16 und
y=+16 sowie Boden- und Deckenquadrat. Das geschieht nur, wenn das Pack ein
eigenes Himmelsprogramm hat und die Sitzung aktiv ist; der Standardrenderer
bleibt unverändert. Die Geometrie ist eine eigene Umsetzung, kein kopierter
Code.

Belegt durch Debug-Packs (Himmelsprogramm schreibt Richtung als Farbe):
Unter Iris erreicht das Programm den ganzen Bereich unter dem Horizont, in
VSDH vorher nur einen Streifen am oberen Bildrand.

## Vanilla-Löschen in `renderLevel` (11.11.2026)

`LevelRenderer.renderLevel` ruft nach `beginWorld` noch `RenderSystem.clear` mit
der Nebelfarbe auf. Da VSDH zu diesem Zeitpunkt schon das Szenenziel des Packs
gebunden hat, überschrieb das `colortex0` mit Nebelfarbe, auch wenn das Pack
eine eigene Löschfarbe festlegt. Mellow (Löschfarbe 0, Himmel erst in
`deferred1` addiert) bekam dadurch einen weißlichen Himmel und helleres
Fernwasser; nachgewiesen mit Debug-Packs (Rohwert von `colortex0` in
`deferred1`: Iris 0, VSDH Nebelfarbe). Iris ersetzt dieses Löschen durch die
Löschpässe des Packs. Jetzt entfernt `WorldShaderLevelMixin.vsdh$worldClear` den
Farbanteil, solange eine Pack-Sitzung aktiv ist; die Tiefe wird weiter
gelöscht. Packs ohne eigene Löschfarbe für `colortex0` erhalten die Nebelfarbe
wie bisher über `ShaderTargetLayout` (`fogClear`).
