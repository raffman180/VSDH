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
