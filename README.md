# VSDH — Entwicklungsstand 0.1.0

VSDH bindet Distant Horizons an den Vulkan-Renderer von **VulkanMod Reforged** an.
Dieser erste Meilenstein enthält die native LOD-Grafikausgabe. **Er enthält noch
keinen Shaderpack-Loader. Complementary Reimagined, Iris und Sodium laufen damit
noch nicht gemeinsam unter Vulkan.**

Das Gesamtprojekt ist damit nicht abgeschlossen. Das erste Shader-Ziel ist
Complementary Reimagined. Eine Unterstützung aller Shaderpacks, aller DH-Versionen
oder aller Grafikkarten ist weder implementiert noch nachgewiesen.

## Festgelegte Entwicklungsumgebung

| Bestandteil | Version |
|---|---|
| Minecraft Java | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| VulkanMod Reforged | 0.3.5-ALPHA+1.21.1 |
| Distant Horizons | 3.3.3 für 1.21.1, Fabric/NeoForge-JAR |

Diese Versionen sind absichtlich eingegrenzt. Abweichende Versionen dürfen erst
nach Schnittstellenprüfung und Spieltests als unterstützt freigegeben werden.
Die Begrenzung verhindert, dass ungetestete Kombinationen still geladen werden.

## Entwicklungs-JAR ausprobieren

1. Ein separates Minecraft-Profil für **1.21.1 / NeoForge 21.1.264** verwenden.
2. VulkanMod Reforged und DH in den oben genannten Versionen installieren.
3. `build/libs/VSDH-neoforge-1.21.1-0.1.0-dev.jar` in dessen `mods`-Ordner legen.
4. **Iris, Sodium und andere Renderer-Ersatzmods nicht in dieses Testprofil legen.**
5. Zuerst in einer neuen Testwelt ohne Shader prüfen. LOD-Erstellung abwarten.

VSDH ist eine Client-Mod. Für diesen Stand ist keine VSDH-Installation auf einem
dedizierten Server vorgesehen. Ein dedizierter Server wurde noch nicht getestet.
Die bestehenden Speicher- und Netzwerkfunktionen bleiben bei Distant Horizons.

Die beiden anderen Mods sind **nicht** in der VSDH-JAR enthalten. Ihre Downloads
und die für den Build geprüften Dateihashes stehen in `libs/README.md`.

## Enthalten

- Eigener Vulkan-Renderpfad für farbige DH-LOD-Geometrie.
- Lichtkarte, transparente LODs, Tiefenkomposition, Nebel und SSAO aus der
  quelloffenen Vulkan-DH-Vorlage, an Reforged / DH 3.3.3 angepasst.
- Kopierte Upload-Daten mit synchronisierter Speicherfreigabe zwischen
  DH-Arbeitsthreads und Renderthread; Vulkan-Operationen bleiben auf dem Renderthread.
- Freigabe der GPU-Ressourcen beim Verlassen einer Welt und zentral verwaltete
  Fenstergrößen-Callbacks.
- Client-seitiger Einstieg; keine Änderungen an Inventaren oder eigenen Weltdaten.

## Bekannte Grenzen

- **Keine Shaderpacks, kein Complementary und keine Iris-/Sodium-Anbindung.**
- Keine texturierten LODs aus neueren DH-Versionen; vorerst farbige LODs.
- Kein zusätzlicher DH-Antialiasing-Pass, keine DH-Drahtgitteranzeige und keine
  benutzerdefinierte DH-API-Geometrie, etwa entfernte Beacons.
- Diese DH-Optionen werden nur für die Sitzung über die DH-API abgeschaltet;
  ihre gespeicherten Benutzerwerte werden nicht überschrieben.
- Die Vanilla-/Reforged-Wolken bleiben aktiv. Der eigene Wolkenrenderer der
  Fabric-Vorlage benötigt neuere VulkanMod-Schnittstellen. Wolken-/LOD-Überdeckung
  ist deshalb gesondert zu prüfen.
- Bisherige Sichtprüfung auf RTX 4070 / NVIDIA-Treiber 621.14. Weitere GPUs,
  Betriebssysteme und große Modpacks sind ungetestet.
- Keine FPS-Zusage und kein kontrollierter Leistungsvergleich.
- Beim direkten Beenden des Clients aus einer laufenden Welt trat einmal
  eine DH-SQL-Meldung `stmt pointer is closed` auf. Der reguläre Ablauf über
  Welt verlassen / Hauptmenü lief im abschließenden Test ohne ERROR/FATAL durch.
  Der direkte Beenden-Pfad ist weiterhin offen; keine Stabilitätsfreigabe.

## Selbst bauen

JDK 21 installieren. Unter PowerShell im Projektordner:

```powershell
.\tools\fetch-dependencies.ps1
.\gradlew.bat build
```

Die ausführbare Mod-JAR liegt in `build/libs/`. Die `-sources.jar` ist der
Java-Quellcode und wird nicht als Mod installiert. Ein vollständiges Quellpaket
benötigt zusätzlich dieses Buildskript, den Wrapper und die Ressourcen.

`verifyDependencies` prüft die tatsächlich verwendeten Fremd-JARs vor dem
Kompilieren. Sie werden nicht in das Ergebnis eingebettet.

## Prüfen

```powershell
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runSmokeClient
```

`runSmokeClient` ist ein separater Entwicklungsablauf: Er erstellt neue Welten
mit Namen `VSDH-Smoke-…` ausschließlich unter `run/saves`, verändert deren Zeit
und Wetter, wechselt die Fenstergröße, erstellt Screenshots und beendet den
Testclient. Vorhandene Spielstände werden nicht ausgewählt oder gelöscht.
Der Testcode unter `src/smoke` ist nicht in der ausgelieferten Mod-JAR.

Der genaue Prüfstand steht in `docs/MIGRATION_STATUS.md`. Ein erfolgreicher
Build oder ein kurzer Spieltest beweist keine vollständige Kompatibilität.

## Diagnose

`config/vsdh.json` enthält `vulkanRenderMode`: 0 = normales Bild, 1 = DH-Tiefe,
2 = SSAO, 3/4 = Nebel, 5 = Normalen, 6 = Minecraft-Tiefe. Änderungen erfordern
einen Neustart. Ungültige Konfigurationsdateien bleiben erhalten; der Start
verwendet dann Standardwerte und protokolliert die Ursache.

Bei Fehlern bitte `logs/latest.log`, die drei Mod-Dateinamen und die verwendete
Welt/Dimension angeben. Ein Screenshot mit sichtbarer Problemstelle hilft.

## Herkunft

Inoffizielle NeoForge-Anpassung der [Vulkan-DH-Erweiterung von Braffolk](https://github.com/Braffolk/distant-horizons-vulkanmod),
Stand `a72ba645188c5fab5f9d48254b6703b659543f0c`. Reforged-Referenz:
`0618965fbedb71ca2ee2e5d4528fcedc4fb27551`. Autoren und Lizenztexte stehen
in `NOTICE`, `LICENSE` und `COPYING`. Keine offizielle Veröffentlichung der
ursprünglichen Projekte.
