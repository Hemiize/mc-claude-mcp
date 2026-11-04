# Claude MCP – Minecraft-Client für Claude Code

![Claude läuft per goto durch die Welt, das HUD zeigt den laufenden Task](docs/screenshot-goto.png)

![Live-Groovy-HUD, Methoden-Hook und Chat-Filter gleichzeitig aktiv](docs/screenshot-hooks.png)

Fabric-Mod für **Minecraft Java 26.3**. Sie startet im Spiel einen MCP-Server (Streamable HTTP), über den Claude Code den Client komplett steuern kann: Zustand lesen, laufen, abbauen, bauen, craften, kämpfen, GUIs bedienen, sich Dinge pro Welt merken, beliebige Java-Methoden per Reflection aufrufen und Groovy-Scripts im Spiel ausführen.

- Endpoint: `http://127.0.0.1:25599/mcp`. Er lauscht nur auf localhost und verlangt `Authorization: Bearer <token>`.
- Token: `config/claudemcp/token.txt`. Wird beim ersten Start erzeugt.
- Einstellungen: `config/claudemcp/config.json`. Unter anderem lassen sich dort `enableScripts` und `enableReflection` abschalten, außerdem gibt es `port`, `hud` und `userOverride`.
- Läuft in Singleplayer und auf Servern, auch auf eigenen öffentlichen Servern. Die Mod ist rein clientseitig. Anticheat-Umgehung ist ausdrücklich **nicht** eingebaut.

## Installation

1. In der Prism-Instanz „Fabric 26.3“ müssen folgende Dateien in `mods/` liegen: `claude-mcp-0.1.0.jar` und `fabric-api` ab Version 0.165.0.
2. Minecraft einmal starten, damit Token und Config angelegt werden.
3. Den Server in Claude Code registrieren (User-Scope):
   ```powershell
   .\register.ps1                      # findet die Prism-Instanz automatisch
   .\register.ps1 -ConfigDir "<instanz>\minecraft\config\claudemcp"
   ```
4. Optional den Skill installieren. Dafür `skill/minecraft-control` nach `~/.claude/skills/` kopieren.

## Bedienung im Spiel

- **HUD** oben links: zeigt, was Claude gerade tut.
- **`End`**: stoppt sofort alle Tasks und Scripts. Die Taste lässt sich unter „Steuerung“ umbelegen.
- **Eigene Eingabe** (WASD, Springen, Schleichen, Mausklick) bricht laufende Bewegungs-Tasks ab und löst das Ereignis `user_override` aus.

## Tools (73)

| Gruppe | Tools |
|---|---|
| Zustand | `get_state`, `get_blocks`, `find_blocks`, `get_entities`, `get_inventory`, `get_screen`, `screenshot` |
| Aktionen | `goto`, `follow`, `look_at`, `set_rotation`, `move`, `mine_block`, `place_block`, `interact_block`, `interact_entity`, `use_item`, `attack`, `select_slot`, `click_slot`, `click_widget`, `click_at`, `type_text`, `press_key`, `open_inventory`, `close_screen`, `craft`, `list_recipes`, `chat`, `run_command`, `client_message` |
| Tasks | `task_status`, `task_wait`, `task_cancel`, `stop_all` |
| Ereignisse | `get_events` (mit Long-Polling), `get_log` |
| Memory | `memory_put`, `memory_get`, `memory_search`, `memory_delete`, `waypoint_set`, `waypoint_list`, `waypoint_delete`, `waypoint_goto` |
| Reflection | `java_find_class`, `java_describe`, `java_get_field`, `java_set_field`, `java_invoke`, `java_new`, `handle_release` |
| Script | `script_eval`, `script_save`, `script_run`, `script_list`, `script_get`, `script_delete`, `hook_register`, `hook_list`, `hook_remove` |
| Methoden-Hooks | `java_hook`, `java_hook_calls`, `java_hook_list`, `java_hook_remove`, `java_hook_enable` |
| Chat-Filter | `chat_rule_add`, `chat_rule_list`, `chat_rule_remove` (+ Hook-Events `chat_in`/`chat_out`) |
| NBT | `nbt_get`, `nbt_set`, `nbt_remove`, `nbt_give` |

Längere Aktionen laufen als Task. Mit `wait` in Sekunden wartet der Aufruf direkt auf das Ergebnis, ohne `wait` kommt sofort eine Task-ID zurück.

## Methoden-Hooks zur Laufzeit

`java_hook` hängt sich zur Laufzeit in **jede** Methode des Spiels oder einer Mod. Technisch hängt sich dafür ein Java-Agent per Self-Attach an den laufenden Prozess, und Byte Buddy schreibt die Klasse per Retransformation um.
- **Ohne Code** werden nur die Aufrufe mitgeschnitten, nachzulesen mit `java_hook_calls`.
- **`before`** ist Groovy-Code, der vor der Methode läuft. `args[i]` ändert Argumente, `hook.cancel(wert)` überspringt die Methode und gibt `wert` zurück.
- **`after`** läuft nach der Methode. Dort stehen `result` und `thrown` zur Verfügung, und `hook.setResult(wert)` ersetzt den Rückgabewert.
- Entfernt man den letzten Hook einer Methode, wird der Original-Bytecode wiederhergestellt.
- JDK-Klassen und die Mod selbst lassen sich nicht hooken.
- Falls der Agent nicht anhängen kann: Das JVM-Argument `-Djdk.attach.allowAttachSelf=true` hilft.

## NBT-Editor

- `nbt_get`, `nbt_set` und `nbt_remove` arbeiten mit Block-Entities, Entities, dem Spieler und Items. Pfade folgen der Syntax von `/data`, zum Beispiel `Items[0].count`. `nbt_give` erzeugt Items aus SNBT, inklusive Komponenten.
- **Singleplayer:** Es werden die echten Daten auf dem integrierten Server geändert.
- **Server:** Blöcke und Entities werden über `/data`-Befehle geändert, dafür braucht man OP. Items lassen sich nur im Kreativmodus ändern, über das Creative-Paket. Spielerdaten lassen sich auf Servern nicht ändern.

## Entwicklung

```powershell
.\gradlew build                 # Jar + Unit-Tests (Pathfinder, Overloads, Memory/Events)
.\gradlew runClient             # Dev-Client (run/), MCP auf Port 25599
.\gradlew runClientGameTest     # End-to-End: echter Client, Testwelt, MCP über HTTP
```

Gradle 9.8.2 (Wrapper), Loom 1.19.3, Fabric API 0.165.2+26.3. Gradle sucht sich ein installiertes JDK 25 selbst (`gradle/gradle-daemon-jvm.properties`), das Standard-Java des Systems darf älter sein.
MCP Java SDK 2.1.0, Jetty 12.1 (ee11), Groovy 5.1.3. Alle Bibliotheken liegen als Jar-in-Jar in der Mod.

### Besonderheiten von 26.3, die man kennen sollte

- Der Code ist nicht mehr obfuskiert und verwendet die Mojang-Namen. Viele Klassen heißen anders als in 1.21, zum Beispiel `GuiGraphicsExtractor`, `mc.gui.screen()` und `gui.setScreen()`. Außerdem gilt `Hud.toggle()` statt `options.hideGui`.
- Tastencodes sind SDL-Scancodes, keine GLFW-Codes. Den richtigen Code liefert `InputConstants.getKey("key.keyboard.w")`.
- Synthetische `mouseClicked`-Events lösen Buttons auf manchen Screens nicht aus. `click_widget` ruft deshalb bei Buttons direkt `onPress` auf.
- Das MCP SDK legt Reactor-Threads an, die keine Daemon-Threads sind. Die Mod setzt eine eigene Scheduler-Factory, damit der Client sauber beendet wird.

## Lizenz und Namensnennung

Copyright 2026 **[Hemiize](https://github.com/Hemiize)**. Lizenziert unter der [Apache License 2.0](LICENSE).

**Namensnennung ist Pflicht.** Wer dieses Projekt oder Teile davon weitergibt, zum Beispiel als Fork, Modpack oder in einer eigenen Mod, muss die Datei [NOTICE](NOTICE) mitliefern. Damit muss Hemiize als ursprünglicher Autor mit Link auf dieses Repository genannt werden (Abschnitt 4 der Apache-Lizenz).

Die mitgelieferten Bibliotheken behalten ihre eigenen Lizenzen. Dazu gehören das MCP Java SDK, Jetty, Groovy, Byte Buddy, Jackson und Reactor. Minecraft ist eine Marke von Mojang/Microsoft. Dieses Projekt ist nicht offiziell und steht in keiner Verbindung zu Mojang oder Anthropic.

