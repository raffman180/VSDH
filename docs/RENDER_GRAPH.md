# Native Shader-Kette: Stand 0.3

Dieser Baustein ist implementiert und mit GPU-Rücklesen geprüft. Die echte
Minecraft-Geometrie ist noch nicht durch die Shaderpack-Programme angebunden.
Die installierte Mod aktiviert deshalb noch keinen vollständigen Shader-Modus.

## Datenfluss und Grenzen

`ShaderChainPlan.prepare` liest das geöffnete Originalpack auf einem Arbeitsthread,
wendet Profil und Programmschalter an und erstellt SPIR-V und eine feste Reihenfolge.
Zip-Dateisystempfade bleiben nicht im Ergebnis. Nicht implementierte erforderliche
Features und Compute-Durchläufe führen zu einer verständlichen Ablehnung.

`ShaderTargetLayout` berechnet Größen und Texel-Speicherbedarf einschließlich beider
Pufferhälften und aller Mipmap-Stufen. Geräteobergrenze und übergebenes Budget werden
vor der ersten Bild-Allokation geprüft. Für Complementary r5.9.3 MEDIUM ergeben die
Deklarationen bei 2560×1440 328.088.712 Bytes (rund 313 MiB) Farbdaten; Schatten,
Tiefenbilder und treiberspezifischer Zusatzbedarf sind darin nicht enthalten.
Das ist eine Berechnung, keine VRAM- oder FPS-Messung.

`NativeColorTargets` besitzt die Bilder. Renderdurchläufe leihen sich nur Ansichten
auf die gewünschten Zielbilder. Die volle Mipmap-Ansicht wird zum Lesen verwendet,
die Ansicht auf Stufe 0 zum Rendern. Die Puffer werden genau nach Pack-Deklaration
geleert; nicht geleerte Puffer erhalten am Frame-Ende bei Bedarf die letzte ALT-
Ausgabe als MAIN-Kopie für den nächsten Frame.

Diese Rückkopie folgt [Iris für Minecraft 1.21.1](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/pipeline/FinalPassRenderer.java).
Ein blindes Vertauschen der beiden Bild-Objekte würde andere Daten in der jeweils
zweiten Hälfte zurücklassen und ist deshalb nicht gleichwertig.

`NativeShaderChain` erzwingt die Stufenfolge BEGIN, PREPARE, DEFERRED, COMPOSITE,
FINAL. Auch leere Stufen werden durchlaufen. Eine neue Frame darf nicht über eine
unfertige alte gelegt werden. Echte Geometrie und Schatten müssen später an den
vorgesehenen Grenzen gezeichnet werden. `geometry` liefert dafür die richtigen
Farbausgaben vor beziehungsweise nach DEFERRED, mit optional geliehener Tiefe.

`NativeWorldDepth` leiht sich die Welt-Tiefe und besitzt nur angeforderte Kopien:

- depthtex0/gdepthtex: aktuelle Tiefe der Welt.
- depthtex2: Kopie vor der deckenden Hand.
- depthtex1: Kopie vor transparenten Blöcken, nach der deckenden Hand.

D32S8/D24S8-Kopien übertragen Tiefe und Stencil als getrennte Vulkan-Aspekte.
Die vorhandene Welt-Tiefe wird beim Schließen dieses Bausteins nicht freigegeben.
Fehlende benötigte Kopierpunkte werden erkannt.

## Ausführung und Lebensdauer

Alle Vulkan-Objekte und Bildoperationen bleiben auf dem Renderthread.
`NativeImageOps` zeichnet Clears, Mipmap-Erzeugung und Kopien in den laufenden
Commandbuffer. Diese neuen Produktionspfade enthalten keine CPU-Rückleseoperation,
keine eigene Queue-Abgabe und kein Warten auf einen Fence. Rücklesen findet nur
im Entwicklungs-Testcode statt.

Die Pack-Pipelines können beim Laden vorbereitet werden, damit deren erste
Erstellung nicht bis zum sichtbaren Frame aufgeschoben werden muss. Ansichten,
Pipelines und Bilder werden bei einer Neuerstellung zusammen geschlossen;
GPU-Freigaben bleiben an den vorhandenen Frame-Lebenszyklus gebunden. Es werden
keine Weltdaten angefasst.

Shader-Farbpuffer verwenden neutrale LOD-Verzerrung. Die bewusste -0.5-Verzerrung
normaler Reforged-Terraintexturen wurde nicht verändert. Unbenutzte Texturen dürfen
Nummernlücken zurücklassen; Bindungsnummer und Position im Vulkan-Layout-Array
werden getrennt behandelt. Dynamische Uniformpuffer sind nach Bindungsnummer sortiert.

## Tatsächliche Prüfung

Das eigene Diagnosepack durchläuft alle Stufen mit bekannten Ergebnissen. Ein
Schachbrettmuster muss in Mipmap 1 den bekannten Mittelwert liefern. Das verhindert,
dass eine fälschlich auf Stufe 0 begrenzte Abfrage den Test besteht. Vier Frames
belegen die Rückkopie gespeicherter Daten; weitere Größen und ein Pack ohne FINAL
belegen Neuerstellung und den Präsentationspfad ohne abschließenden Shader.
Tiefen-/Stencil-Sentinels prüfen die drei Tiefenformate getrennt.

Für das echte Complementary-Pack werden neun Vollbild-Pipelines und elf Zielpuffer
als Vulkan-Objekte angelegt. Der FINAL-Durchlauf wird mit kontrollierten Eingaben
gezeichnet. Die komplette echte Welt-Kette wird damit noch nicht gezeichnet.

Die bisherigen MRT-/DH-Prüfungen laufen weiter: zwei neue Welten, Größenwechsel,
Nacht/Regen, Entladen/Neuinitialisieren und reguläres Beenden. Der Testcode und die
Diagnosepacks liegen ausschließlich unter `src/smoke` und fehlen in der Mod-JAR.
