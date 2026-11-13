# Weltwerte — 0.6.0-dev

## Tatsächlich gelesene Uniforms

Shaderpacks deklarieren oft denselben großen Uniformblock für viele Programme.
Eine Deklaration allein bedeutet nicht, dass ein Programm diesen Wert verwendet.
UniformUsage untersucht deshalb den optimierten SPIR-V-Code beider Shaderstufen.
Es ordnet Zugriffe über die tatsächlichen Member-Byteoffsets dem unveränderten
std140-Layout zu. Entfernte oder neu nummerierte Member und entfernte Debugnamen
dürfen diese Zuordnung nicht verändern.

Direkte OpAccessChain-/OpInBoundsAccessChain-Zugriffe zählen das erste Struct-Member;
ein dynamischer Arrayindex innerhalb dieses Members bleibt zulässig. Ganze
Blockzugriffe, unbekannte Pointer-Aliase und Funktionsparameter werden konservativ
behandelt: alle Member bleiben erforderlich. Unbekannte Gruppendekorationen oder
ungültige Offsettabellen werden abgewiesen. Grundlage ist die
[SPIR-V-Spezifikation](https://registry.khronos.org/SPIR-V/specs/unified1/SPIRV.html).

NativeGraphicsProgram speichert die aktive Memberliste einmal bei der Erstellung.
WorldUniforms validiert und beschreibt diese Liste. Die vollständigen Offsets und
die Größe des Uniformpuffers bleiben erhalten. Unbenutzte Deklarationen brauchen
keinen erfundenen Provider; tatsächlich gelesene unbekannte Werte bleiben Fehler.
WorldHostAudit führt sowohl aktive fehlende Werte als auch unbediente Deklarationen
auf. Die alte Zahl 59 war die zweite Kategorie. Vor den neuen Providern fehlen in
der Oberwelt 31 tatsächlich gelesene Werte, danach 17; Nether 14, Ende 12.

## Neue Provider für Minecraft 1.21.1

BuiltinWorldValues übernimmt Kamera, Entity und ClientLevel genau einmal pro Frame:

- Ganze und gebrochene aktuelle/vorherige absolute Kamerakoordinaten. Negative
  Koordinaten verwenden floor, damit der Bruch positiv bleibt. Die Zerlegung
  erfolgt vor einer Umwandlung nach float, auch an der Weltgrenze.
- Augenposition, Kamera minus interpolierte Augenposition und Blickrichtung.
- Block-/Himmelslicht an der Entity-Augenhöhe, jeweils mit Faktor 16; Wasser-,
  Lava- und Pulverschneekennung aus der tatsächlichen Kamera.
- Blindheit, Darkness-Blend und Nachtsicht aus realen Effekten, mit gesonderter
  Prüfung auf vorhandene Nachtsicht vor dem Vanilla-Aufruf; Conduit-Wasserwert.
- Tatsächliche Wolkenhöhe, Mondphase, gedrehter Sonnenwinkel und Gamma-Einstellung.

Atlasgröße und gl_Fog folgen dem Zustand beim jeweiligen Zeichnen. Die Atlasgröße
stammt aus dem wirklich gebundenen Terrainbild. Der 32-Byte-OfFog-Struct enthält
Farbe, Dichte, Start, Ende und Skala. Dichte ist außerhalb Wasser null; im Wasser
werden Wassergewöhnung und HAS_CLOSER_WATER_FOG berücksichtigt. Nebelstart/-ende
dürfen nicht vor FogRenderer.setupFog aus einem veralteten Frame übernommen werden.

Die 18 neuen Namen sind keine vollständige Implementierung des Iris-Vertrags.
Insbesondere fehlen geglättete Werte, Held-Item-Materialtabellen, benutzerdefinierte
Packausdrücke und Schattenmatrizen. Die bisherigen float-cameraPosition-Werte sind
noch nicht mit dem Iris-Origin-Rebasing für große Koordinaten versehen; die neue
Int-/Fract-Zerlegung allein löst diese gesonderte Aufgabe nicht. Atlastracking für
weitere Geometriefamilien muss beim späteren Anschluss dieser Familien ergänzt
werden. Keine Zusage für nicht geprüfte Kombinationen oder Shaderpacks.

## Prüfung

UniformUsageTest verwendet echte shaderc-Ausgabe mit dynamischen Arrayzugriffen
und unbenutzten Zwischenfeldern sowie kleine SPIR-V-Fixtures für konservative
Alias-/Blockfälle und ungültige Tabellen. CameraCoordinatesTest prüft negative
Koordinaten, Weltgrenzpräzision und nicht endliche Eingaben.

Der eigene Welt-Diagnosepack schreibt die neuen Werte in fünf zusätzliche
RGBA16F-Ziele. BuiltinWorldProbe sucht zuerst einen echten Terrain-Normalenpixel
und liest anschließend genau dort die Werte zurück. Erwartungswerte stammen
direkt aus Kamera/Spielzustand, nicht aus dem Providerpuffer. Drei Szenen sind
verpflichtend: Luft, Wasser an negativen Koordinaten sowie drei echte Statuseffekte.
Eine absichtlich unbekannte, aber unbenutzte Uniform prüft zugleich die neue
Aktivitätsfilterung. Rücklesen und zusätzliche Diagnosebilder gehören nur zum
Smoke-SourceSet; Produktionsshader warten dafür nicht auf die GPU.

Der Pool und die Statuseffekte werden ausschließlich in einer frisch erzeugten
isolierten Testwelt angelegt. Bestehende Welten werden nicht verwendet.
verify-0.6-client-2.log besteht mit allen drei Werte-Probes und den bisherigen
Weltketten-/FINAL-/Scale-/Weltwechselprüfungen. Kein ERROR/FATAL. Der erste
Lauf verify-0.6-client.log scheiterte an Diagnosewerten im Alpha-Kanal: bei
Transparenz wurden die Werte vermischt (1.0 erwartet, 0.75 gemessen). Korrigiert
mit Alpha=1 sowie geraden/ungeraden Pixelspalten für zusätzliche RGB-Messwerte.
Der zweite Lauf vergleicht alle Werte ohne diese unbeabsichtigte Vermischung.

36 CPU-Tests, Build und verifyBundledLwjgl bestehen; alle Build-Tasks erneut ohne
Gradle-Build-Cache ausgeführt. Einzelheiten: verification/0.6.0-dev.json.

## 0.26: DH-Kennzahlen ohne DH-Programme

`dhNearPlane`, `dhFarPlane` und `dhRenderDistance` stellt Iris jedem Pack
bereit. Bliss liest sie auch ohne seine DH-Programme. VSDH lehnte Bliss bei
`"shaderDistantHorizons": false` deshalb ab. Diese drei Werte sind jetzt
immer verfügbar und stammen aus denselben DH-Quellen wie im DH-Modus. Die
DH-Matrizen bleiben an die DH-Programme gebunden. Ohne DH-Programme verdeckt
der Nebel des Packs die Ferne, wie bei einem Pack ohne DH-Unterstützung.

## `near` und die tatsächliche Projektion (11.11.2026)

Unter NeoForge greift Reforgeds JOML-Mixin (`Matrix4fM`) nicht: Ein
Laufzeittest (`new Matrix4f().perspective(1,1,.05,256).m32()`) liefert -0,1,
also die OpenGL-Form. Die Projektion, die beim Zeichnen ankommt, wirkt in
Vulkan deshalb wie eine mit Near-Ebene 0,1. Alle veröffentlichten Matrizen
sind untereinander und mit dem Tiefenpuffer stimmig. Nur `near` war fest
0,05. Packs, die Tiefe mit `near` linearisieren, erhielten so etwa halbe
Entfernungen. `near` folgt jetzt der Near-Ebene der veröffentlichten
Projektion (`WorldUniforms.capture`). Prüfbar mit `-Dvsdh.uniformDump=...`:
die Zeile `PROJECTION_DUMP` enthält Probe, Matrixwerte und Near/Far.

## Ausrichtung wie OpenGL (11.11.2026 abends)

`gbufferProjection`, `shadowProjection`, die DH-Projektion und die Projektion
einzelner Zeichnungen werden jetzt unverändert (Y nicht gespiegelt)
veröffentlicht: `gbufferProjection[1][1]` ist positiv wie unter Iris. Die
Pack-Bilder liegen dafür wie in OpenGL mit Zeilen von unten nach oben vor;
`texcoord.y` und `gl_FragCoord.y` wachsen nach oben. Erst das fertige Bild wird
einmal gespiegelt in das Hauptziel kopiert. Vorher war `[1][1]` negativ, was
Packformeln wie `fovScale = gbufferProjection[1][1] / 1.37` das Vorzeichen
umkehrte. Geprüft mit einem Probe-Pack, das Uniforms als Farbbalken ausgibt:
alle verglichenen Werte gleich Iris außer `near` (0,1 statt 0,05, siehe oben).
Details: `NATIVE_PACK_FEATURES.md`, Abschnitt „Iris parity fixes“.
