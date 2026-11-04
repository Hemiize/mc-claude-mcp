---
name: minecraft-control
description: Control the user's running Minecraft Java client (26.3) through the "minecraft" MCP server (Claude MCP mod) - walk, mine, build, craft, fight, read the world, remember places, call Java via reflection or run Groovy inside the game. Use when the user asks to do something in Minecraft, in their world or on their server.
---

# Minecraft control (Claude MCP mod)

The MCP server lives inside the user's Minecraft client (Fabric mod `claudemcp`, http://127.0.0.1:25599/mcp).
It only exists while the game runs. If the tools fail with connection errors, ask the user to start Minecraft.

## Ground rules

- The user is often playing at the same time. Their own movement/attack input cancels your movement tasks
  (event `user_override`) - do not immediately restart; ask or wait.
- `chat` messages are visible to every player on a server. Use `client_message` for notes only the user sees.
- On multiplayer servers (`world_id` starts with `mp-`) commands depend on the user's permissions.
- Stop everything with `stop_all` when something goes wrong (the user can also press `End`).

## Workflow

1. `get_state` first (position, health, dimension, screen, world_id).
2. Perceive: `find_blocks` (nearest of given ids), `get_blocks` (box around a point, grouped by id),
   `get_entities`, `get_inventory`, `get_screen`, `screenshot` when the visual matters.
3. Act with tasks: `goto`, `mine_block`, `place_block`, `interact_block`, `craft`, `attack`, `follow`, `use_item`, `move`.
   They return a task. Pass `wait` (seconds) for short ones, otherwise poll `task_status` / `task_wait`.
   Only one body task runs at a time - starting a new one cancels the previous.
4. Watch `get_events(since=<last_seq>, wait=…)` for chat, damage, death, task_end, user_override.
5. Remember things with `waypoint_set` / `memory_put` (scope = current world by default, or `global`).
   Check `memory_search` / `waypoint_list` at the start of a session in a known world.

## Tips

- `goto` with `range` > 0 for "near" targets; exact targets inside the ground are moved to the nearest standable block.
  `allow_break: true` lets it dig through.
- `mine_block` walks into reach, picks the best tool and collects the drops.
- `craft` uses the recipe book: 2x2 recipes anywhere, 3x3 need an open crafting table (`interact_block` on it).
  `list_recipes` shows what is unlocked/craftable.
- Containers: `interact_block` the chest, then `get_screen` (menu_slot indices) and `click_slot`
  (mode `quick_move` = shift-click). `close_screen` afterwards.
- GUIs: `get_screen` lists widgets; `click_widget` by text/index, `type_text`, `press_key` (names like `enter`, `escape`).

## Reflection (java_*)

Mojang names (unobfuscated since 26.1). Roots: `$mc`, `$player`, `$level`, `$gameMode`, `$connection`, `$screen`, `$options`, `$gui`.
- `java_find_class {pattern}` → `java_describe {target, filter}` → `java_get_field` / `java_set_field` / `java_invoke` / `java_new`.
- Objects come back as handles `h:N` (weak; `pin: true` keeps them). Pass handles as args.
- Note: 26.x key codes are SDL scancodes (`InputConstants.getKey("key.keyboard.w")`), not GLFW codes.

## Groovy (script_*, hook_*)

`script_eval {code}` runs on a worker thread; game objects must be touched inside `api.onMain { … }`.
Variables: `mc`, `player`, `level`, `gameMode`, `screen` (live), `api`, `memory`, `args`, `out`.
Useful `api` methods (blocking): `walkTo(x,y,z[,range])`, `mine(x,y,z)`, `place(x,y,z,item)`, `use(x,y,z)`,
`craft(item,n)`, `attack(id)`, `useItem(ticks)`, `keys([forward:true])`, `releaseKeys()`, `look(x,y,z)`,
`select(slot)`, `selectItem(id)`, `chat(msg)`, `command(cmd)`, `say(msg)`, `sleepTicks(n)`, `state()`,
`inventory()`, `entities(r)`, `findBlocks(id,r,limit)`, `emit(type,map)`, `handle(obj)`.
`sync: true` runs directly on the client thread (default limit 250 ms) - good for quick reads.
Save reusable scripts with `script_save`; `hook_register {event, code}` runs code on tick/chat/damage/hud/key/... events
(must be fast; for `hud` use `g.text(font, "text", x, y, (int) 0xFFFFFFFFL)` - ARGB colors above 0x7FFFFFFF are Long in Groovy and need the int cast).

## Runtime method hooks (java_hook*)

`java_hook {class, method, signature?, before?, after?, max_calls?}` patches any game/mod method at runtime (Java agent).
- No code = trace calls → `java_hook_calls {id}` (args, result, thread). Use `max_calls` on hot methods (tick/render).
- `before`: vars `self`, `args` (Object[], assign `args[0] = ...`), `method`, `hook`; `hook.cancel(value)` skips the original.
- `after`: also `result`, `thrown`; `hook.setResult(v)` replaces the return value.
- Hooks run on the calling thread (render/server/netty) with a 50 ms limit. Remove with `java_hook_remove {id|"all"}` - original bytecode is restored.
- Find the method first with `java_describe {target, filter}`; overloaded methods need `signature` like `"(float)"`.

## Chat filter

- `chat_rule_add {pattern, direction: in|out|both, kind: all|chat|game|command, action: hide|replace|notify, replacement}`.
- For logic: `hook_register {event: "chat_in" | "chat_out", code}`. Variables `text` and `kind`. Return `false` to block, a String to replace, `null` to leave it unchanged.
- Events: `chat_filtered`, `chat_rewritten`, `chat_match`.

## NBT editor

- `nbt_get {target: block|entity|player|item, x/y/z | entity_id | slot, path?}`. The result is SNBT. `path` uses /data syntax, for example `Items[0]`.
- `nbt_set {…, snbt}`. Merges a compound; with `path` it sets one value; `mode: "replace"` replaces everything.
- `nbt_remove {…, path}`.
- `nbt_give {snbt: '{id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":"X"}}', slot?}`.
- Singleplayer edits the server-side truth. On servers, edits go out as `/data` commands (permission needed); items work only in creative mode.

Example - chop the nearest 5 logs:
```groovy
5.times {
  def log = api.findBlocks("oak_log", 24, 1)
  if (!log) return
  def p = log[0].pos
  api.mine(p[0], p[1], p[2])
}
api.inventory().items.findAll { it.id.endsWith("_log") }
```
