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
Dadurch können außerhalb des Blicks liegende Chunks Schatten werfen. Der aktuelle
Ansatz zeichnet konservativ alle geladenen Chunks und benötigt später eine
packgerechte Schatten-Sichtbarkeitsprüfung. Es gibt keine FPS-Zusage. Entitäten,
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
