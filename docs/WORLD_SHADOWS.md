# Native Schatten und Packtexturen

Die Weiterarbeit nach 0.6 ergänzt Schattenkamera, Schattenzeichnung und echte
Packtexturen. Das Gesamtziel vollständiger Complementary-/DH-Unterstützung bleibt
offen. Der Standardmodus installiert weiterhin keinen Shader-Host automatisch.

ShadowCamera berechnet die orthografische Sonnen-/Mondansicht aus Packdirektiven,
Sonnenwinkel und tatsächlicher Kamera. Die GL-Tiefe wird erst im nativen Vertex-
Epilog umgesetzt; die Projektions-Y-Zeile entspricht den nativ abgetasteten Bildern.
Raster-Snapping verwendet den Rest der double-Koordinaten vor der float-Umwandlung,
damit Unterblockpräzision auch an der Weltgrenze erhalten bleibt. Inverse Matrizen,
Projektionsgrenzen, Tag/Nacht und ungültige Parameter sind separat getestet.

ShadowPackPlan kompiliert das tatsächliche shadow-Programm samt Alpha-Test und
Ausgabezuordnung. NativeWorldShadows besitzt zwei D32-Tiefenbilder und die
angeforderten Schattenfarben. Opakes Terrain wird zuerst gezeichnet, danach die
Tiefe nach shadowtex1 kopiert und Transparenz nach shadowtex0 ergänzt. Bilder,
Framebuffer und Pipelines werden über die vorhandene verzögerte GPU-Verwaltung
freigegeben. Größen- und Speichergrenzen werden vor der Allokation geprüft.

Die Schattenqueues werden aus geladenen und hochgeladenen RenderSections gebildet.
Die Kamera-Queues werden weder verändert noch als Licht-Sichtbarkeit benutzt.
Dadurch können außerhalb des Blicks liegende Chunks Schatten werfen. Seit 0.26
wählt ShadowCuller pro Frame dieselbe Form wie Iris: `shadow.culling`, ein
Geometrie-Shader am Schattenprogramm (Voxelisierung), `shadowDistance ×
shadowDistanceRenderMul` und `voxelDistance` entscheiden zwischen einem Würfel
um die Kamera, dem in Lichtrichtung verlängerten Sichtfeld und der Safe-Zone.
WorldRenderer prüft zuerst ganze Chunk-Bereiche und danach Sektionen. Die
Prüfung ist monoton, deshalb ändert die Bereichsprüfung das Ergebnis nicht.
ShadowCullerTest prüft per Strahl-Abtastung, dass kein Schattenwerfer eines
sichtbaren Punktes verworfen wird. Ein absichtlich falsches Culling lässt den
Test scheitern. Bei ungültigen Matrizen zeichnet der Pass wieder alles.
Mit `indirectDraw` sammelt der Schattenpass seine Draws pro Bereich in eigenen
Indirect-Puffern pro Frame. Es gibt keine FPS-Zusage. Entitäten,
Blockentitäten und DH-LODs sind noch keine eigenen Schattenwerfer dieses Passes.
Schatten-Depth-Mipmaps und Perspektiv-Schattenkameras sind noch nicht umgesetzt.

entityId=0 wird ausschließlich für den Terrain-/Terrain-Schattenkontext gesetzt:
Hier wird keine Entity gezeichnet. Die tatsächlichen Entity-Materialtabellen und
scoped IDs für später anzuschließende Entity-Programme bleiben erforderlich.
Unbekannte Pflichtwerte werden weiter abgewiesen.

PackTexturePlan liest aktive Texturdefinitionen und PNG-Daten auf dem Worker.
PNG-Rohkanäle werden ohne unbeabsichtigte Graustufen-Gammaumrechnung erhalten.
Nach Schließen des Packs bleiben nur Strings und kopierte Pixel erhalten.
NativeWorldTextures lädt diese Daten über das vorhandene Staging-System hoch.
Sitzungseigene Bilder werden freigegeben; der tatsächliche Minecraft-Atlas bleibt
geliehen. Raw- und hochauflösende 16-Bit-Kanalformate sowie weitere Spielressourcen
benötigen noch Erweiterungen. Diese Grenzen werden explizit gemeldet.

ShaderChainPlan reicht die aktiven Stage-Texturersetzungen in SamplerPlan weiter;
damit liest beispielsweise ein deferred-Lookup die Packdatei statt versehentlich
einen gleichnamigen GBuffer. Die vorhandenen Buffer-Flip-Regeln bleiben maßgeblich.
Terrain-Eingaben aus Farbzielen werden am richtigen Punkt vor/nach Deferred gelesen;
gleichzeitiges Lesen und Schreiben desselben Vulkan-Bildes bleibt ein Fehler.

verify-0.7-client.log besteht mit Formeln/Handlicht/Wetness, verify-0.8-client.log
mit nativer Schattenzeichnung, verify-0.9-client.log zusätzlich mit echten
PNG-/Noise-Farbassertionen. Alle Läufe nutzen zwei isolierte Welten. Nicht mit
vollständiger Darstellung aller Geometriefamilien oder shaderseitigem DH verwechseln.
Der optionale echte Pack-Weltlauf heißt runSmokeClient -PsmokeLivePack=true mit dem
separat bezogenen Pack als -PsmokeShaderPack. Seine tatsächlichen Ergebnisse und
Sichtfehler müssen anhand des neuesten Logs und der Screenshots bewertet werden.

## Befund im echten Pack und Korrektur

Die ersten echten Packbilder zeigten Wolken über dem Wasser. Der bloße Nachweis
nichtschwarzer Pixel hatte diesen Fehler übersehen. CPU-Matrizen und Wolkenhöhe
waren plausibel; eine nur im Speicher instrumentierte Fassung des tatsächlichen
deferred1-Shaders las jedoch Blick-Y +0.871 statt -0.871 zurück.

Reforgeds Matrix4fM erzwingt bei den gewöhnlichen Perspective-Aufrufen 0..1-Tiefe.
ForwardDepthProjection übersetzt die Z-Zeile jetzt mit 2*rowZ-rowW in den GL-Raum
des Packs. Zusätzlich bleibt getDepthFar im Shadermodus bei Minecraft 1.21.1s
getRenderDistance()*4, während der Standardmodus seine bisherige Unendlichkeit
behält. Die JOML-Infinity-Epsilon-Projektion ließ Tiefe 1 hinter der Kamera landen.
Shader-GPU-Probe, drei neue CPU-Tests und Sichtprüfung belegen die Korrektur:
Wolken liegen im Himmel. Ein heller Übergang im Wasser bleibt sichtbar und ist
weiter zu untersuchen, insbesondere zusammen mit der noch fehlenden Pack-DH-Ausgabe.

verify-realpack-world-7.log besteht mit der korrigierten Richtung bei beiden
Rendermaßstäben und anschließendem Rückwechsel zum Standardmodus. Vollständige
Packkompatibilität folgt daraus nicht. Entsprechende Fehlerläufe bleiben erhalten.

## Weitere Schattenziele (11.11.2026 abends)

- `HIGHER_SHADOWCOLOR` wird wie unter Iris angekündigt; ein Pack, das es deklariert,
  erhält bis zu acht `shadowcolor`-Ziele.
- `shadowcolorimgN` in `shadowcomp`-Computes bindet Schattenziel N als Speicherbild
  (Speicher-Nutzung wird beim Anlegen gesetzt).
- Schattenprogramme dürfen Schattenziele lesen, die derselbe Durchgang nicht
  schreibt (Rethinking Voxels liest `shadowcolor2` für wogendes Wasser). Ein Ziel,
  das gelesen und zugleich geschrieben würde, wird mit Meldung abgelehnt.
- Angelegt wird jedes `shadowcolorN`, das ein Programm tatsächlich abtastet; durch
  eine Pack-Textur ersetzte (`texture.gbuffers.shadowcolor3=…`) nicht.
- Im Spiel geprüft: Rethinking Voxels mit Profil HIGH und `WATER_STYLE_DEFINE=3`
  (interaktives Wasser) startet ohne Fehler; Wasserbereiche wie Iris
  (Mittel 89/122/164 gegen 91/124/167).
