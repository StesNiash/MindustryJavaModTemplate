---
name: mindustry-mod
description: Guide for creating Mindustry Java mods — project structure, API references, build process, and common patterns.
---

# Mindustry Java Modding Guide

## Project Structure

```
project/
├── build.gradle          # Gradle build config (Java 8 target, Mindustry dependency)
├── mod.hjson             # Mod metadata (name, main class, version, minGameVersion)
├── gradle.properties     # JVM args for build
├── gradlew / gradlew.bat # Gradle wrapper
└── src/
    └── <package>/
        └── <MainMod>.java # Main mod class extending Mod
```

## Key Files

### `mod.hjson` — Mod metadata
```
displayName: "Mod Display Name"
name: "internal-mod-name"         # internal name, used for sprite prefix
author: "YourName"
main: "com.example.MyMod"         # fully qualified main class
description: "What the mod does."
version: 1.0
minGameVersion: 154               # minimum game build required
java: true                        # required for Java mods
hidden: true                      # optional — allows mod in multiplayer without server-side install (use if mod doesn't add content that must exist on server)
```

> **`hidden: true`** — помечает мод как скрытый (не отображается в списке модов в главном меню). Используется для клиентских модов, которые не добавляют новый контент (блоки, предметы, юниты), а только изменяют поведение игры через события. Такой мод работает в мультиплеере без установки на сервере, так как серверу не нужно знать о новом контенте. Если мод добавляет новый контент, `hidden: true` ставить нельзя — иначе сервер не сможет загрузить этот контент и произойдёт рассинхронизация.

### `build.gradle` — Essential setup
- Target Java 8 (`--release 8` compiler flag)
- Source compatibility Java 17 (for language features, Jabel desugars back to Java 8)
- Mindustry dependency via ivy from GitHub releases
- `sourceSets.main.java.srcDirs = ["src"]`

## Mindustry API Reference (v157)

### Event System (`arc.Events`)
```java
import arc.Events;
import mindustry.game.EventType.*;

// Class-based events
Events.on(BlockBuildBeginEvent.class, e -> { ... });
Events.on(ClientLoadEvent.class, e -> { ... });
Events.on(WorldLoadEvent.class, e -> { ... });

// Trigger-based (runs every frame/phase)
import mindustry.game.EventType.Trigger;
Events.run(Trigger.update, () -> { ... });           // every game tick
Events.run(Trigger.beforeGameUpdate, () -> { ... });
Events.run(Trigger.afterGameUpdate, () -> { ... });
```

### Key Events (`mindustry.game.EventType`)
| Event Class | Fields | Fires When |
|---|---|---|
| `BlockBuildBeginEvent` | `tile`, `team`, `unit`, `breaking` | Construction/deconstruction begins |
| `BlockBuildEndEvent` | `tile`, `team`, `unit`, `breaking`, `config` | Construction/deconstruction finishes |
| `BuildSelectEvent` | `tile`, `team`, `builder`, `breaking` | Building selected for build/break |
| `ClientLoadEvent` | — | Client finishes loading into a game |
| `WorldLoadEvent` | — | World fully loaded |
| `WorldLoadEndEvent` | — | World load complete |
| `TileChangeEvent` | `tile` | Tile block changes |
| `TilePreChangeEvent` | `tile` | Before tile block changes |
| `ConfigEvent` | `tile`, `player`, `value` | Building configured |
| `UnitCreateEvent` | `unit`, `spawner` | Unit created |
| `UnitDestroyEvent` | `unit` | Unit destroyed |
| `TapEvent` | `player`, `tile` | Player taps a tile |

**Note:** `BlockBuildBeginEvent` does NOT have a `block` field. Check `tile.block()` or `tile.build.block` to determine what block is involved.

### Tile API (`mindustry.world.Tile`)
```java
tile.x, tile.y                              // position (short)
tile.build                                  // Building entity on tile (null if empty)
tile.block()                                // Block type on this tile
tile.floor()                                // Floor block
tile.team()                                 // Team owning the tile
tile.setBlock(Block, Team, int rotation)    // Set block directly
tile.setNet(Block, Team, int rotation)      // Set block over network
tile.remove()                               // Remove block
tile.removeNet()                            // Remove block over network
```

### Building API (`mindustry.gen.Building`)
```java
build.block                                 // Block type (public transient, mutable!)
build.team                                  // Team
build.tile                                  // Tile this building is on
build.health                                // Health (float)
build.maxHealth                             // Max health
build.items                                 // Item inventory
build.liquids                               // Liquid inventory
build.power                                 // Power status
```

### Call API (`mindustry.gen.Call`) — Network-synced operations
```java
Call.constructFinish(Tile, Block, Unit, byte, Team, Object);  // Finish construction
Call.deconstructFinish(Tile, Block, Unit);                     // Finish deconstruction
Call.setTile(Tile, Block, Team, int rotation);                 // Set tile over network
Call.beginPlace(Unit, Block, Team, int x, int y, int rotation, Object config);  // Start placement
Call.beginBreak(Unit, Team, int x, int y);                     // Start breaking
Call.removeQueueBlock(NetConnection, int x, int y, boolean);   // Remove from queue
Call.setFloor(Tile, Block floor, Block wall);                  // Set floor/wall
Call.setTeam(Building, Team);                                  // Change team
```

### Content & Blocks (`mindustry.content.Blocks`)
```java
import mindustry.content.Blocks;
// All blocks are static fields:
Blocks.coreShard, Blocks.coreFoundation, Blocks.coreNucleus
Blocks.vault, Blocks.container
Blocks.conveyor, Blocks.titaniumConveyor, Blocks.plastaniumConveyor
Blocks.mechanicalDrill, Blocks.pneumaticDrill, Blocks.laserDrill
Blocks.duo, Blocks.scatter, Blocks.scorch, Blocks.hail, Blocks.arc
Blocks.copperWall, Blocks.titaniumWall
Blocks.solarPanel, Blocks.combustionGenerator, Blocks.steamGenerator
Blocks.commandCenter
// etc. — Blocks.* contains all vanilla blocks
```

### Units (`mindustry.gen.Groups`)
```java
Groups.player       // All players (Seq<Player>)
Groups.unit         // All units (Seq<Unit>)
Groups.building     // All buildings (Seq<Building>)
Groups.tile         // Not a group, use Vars.state.teams
```

### Teams (`mindustry.game.Teams`)
```java
Vars.state.teams.present          // Seq<TeamData> — teams active in game
Vars.state.teams.active           // Seq<TeamData> — all teams with cores

Teams.TeamData data = ...;
data.team                         // Team
data.plans                        // Queue<BlockPlan> — build queue plans
data.cores                        // Seq<CoreBuild> — core buildings
data.players                      // Seq<Player> — players on this team
data.buildings                    // Seq<Building> — all buildings on this team
data.units                        // Seq<Unit> — all units on this team
```

### BuildPlans (`mindustry.game.Teams.BlockPlan` and `mindustry.entities.units.BuildPlan`)

**BlockPlan** (used in `TeamData.plans` queue — long-term queue):
```java
Teams.BlockPlan plan = ...;
plan.x, plan.y, plan.rotation       // position and rotation (all final)
plan.block                          // Block type (FINAL — cannot modify!)
plan.config                         // config (final)
plan.removed                        // boolean, mutable — mark as removed
```

**BuildPlan** (used during active building):
```java
BuildPlan plan = ...;
plan.x, plan.y, plan.rotation       // position and rotation
plan.block                          // Block type (mutable!)
plan.breaking                       // boolean
plan.config                         // config
plan.progress                       // build progress
```

### Player API (`mindustry.gen.Player`)
```java
player.team()                       // Team
player.unit()                       // Unit controlled by player
player.name()                       // Player name
player.con()                        // NetConnection
```

### Vars (`mindustry.Vars`)
```java
Vars.state                          // GameState (null before game starts)
Vars.state.isGame()                 // true when in game
Vars.state.isMenu()                 // true when in menu
Vars.state.teams                    // Teams
Vars.state.rules                    // Game rules
Vars.state.map                      // Current map
Vars.player                         // Local player (singleplayer)
```

### UI (`mindustry.ui.dialogs.BaseDialog`)
```java
BaseDialog dialog = new BaseDialog("title");
dialog.cont.add("text").row();
dialog.cont.image(someTexture).pad(20f).row();
dialog.cont.button("OK", dialog::hide).size(100f, 50f);
dialog.show();
```

### Logging
```java
Log.info("message");
Log.info("formatted {} {}", arg1, arg2);
Log.err("error: {}", errorMessage);
```

## Common Patterns

### Replacing blocks in the build queue
```java
Events.run(Trigger.update, () -> {
    if (Vars.state != null && Vars.state.isGame()) {
        for (Teams.TeamData data : Vars.state.teams.present) {
            Queue<Teams.BlockPlan> plans = data.plans;
            int size = plans.size;
            for (int i = 0; i < size; i++) {
                Teams.BlockPlan plan = plans.get(i);
                if (!plan.removed && plan.block == Blocks.coreShard) {
                    plan.removed = true;
                    plans.addLast(new Teams.BlockPlan(plan.x, plan.y, plan.rotation, Blocks.vault, plan.config));
                }
            }
        }
    }
});
```

### Changing building in progress
```java
Events.on(BlockBuildBeginEvent.class, e -> {
    if (!e.breaking) {
        Building build = e.tile.build;
        if (build != null && build.block == Blocks.coreShard) {
            build.block = Blocks.vault;  // continues building with same progress
        }
    }
});
```

### Adding custom content (blocks, items, units)
In `loadContent()`, register new content types extending Mindustry base classes:
```java
@Override
public void loadContent() {
    // Content is registered automatically via static field initialization
}
```

## Build & Run

### Build
```
gradlew.bat jar          # Windows
./gradlew jar            # Linux/macOS
```

Output: `build/libs/<project-name>Desktop.jar`

### Install
Copy the `.jar` to Mindustry's `mods/` folder:
- Windows: `%appdata%/Mindustry/mods/`
- Linux: `~/.local/share/Mindustry/mods/`
- macOS: `~/Library/Application Support/Mindustry/mods/`

### Clean build
```
gradlew.bat clean jar
```

## Finding More API Info

### Inspect the Mindustry jar directly
```bash
# List all classes in a package
jar tf "Mindustry-v157.jar" | Select-String "game/EventType"

# Decompile a specific class
javap -p -classpath "Mindustry-v157.jar" 'mindustry.game.EventType$BlockBuildBeginEvent'

# List all methods of a class
javap -classpath "Mindustry-v157.jar" mindustry.gen.Call | Select-String "public static"
```

The Mindustry jar is cached at:
`~/.gradle/caches/modules-2/files-2.1/Anuken/Mindustry/<version>/<hash>/Mindustry-<version>.jar`

### Online resources
- Mindustry source code: https://github.com/Anuken/Mindustry
- Mindustry Javadoc: https://javid.ddns.net/t2/stuff/javadoc/
- Mindustry modding Discord: https://discord.gg/mindustry

## Version Notes (v157)
- Java 8 target, Java 17 source (Jabel plugin for desugaring)
- Uses `arc` library (not LibGDX directly)
- `BlockBuildBeginEvent`/`BlockBuildEndEvent` have NO `block` field
- `Call.constructBegin` / `Call.deconstructBegin` do NOT exist — use `Call.beginPlace` / `Call.beginBreak`
- `Tile.build` is a public field, NOT a method
- `Building.block` is `public transient` — mutable
- `arc.struct.Queue` is a circular buffer, NOT `java.util.Queue`
- `arc.struct.Seq` is the primary list type, similar to ArrayList
