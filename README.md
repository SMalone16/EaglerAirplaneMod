# EaglerAirplaneMod

**Evan + Liam's Plane System** for the Pawling classroom Eaglercraft/Paper server.

This plugin deliberately uses a lightweight **Plane Mode** instead of a custom Minecraft vehicle entity. That keeps the mechanic practical for the browser-based 1.12.2 client while the backend runs Paper 1.21.11.

## Gameplay

### Craft a Plane Kit

The recipe uses materials that exist on the older browser client:

```
Iron Block | Diamond | Iron Block
Redstone   | Furnace | Redstone
Iron Block | Diamond | Iron Block
```

The crafted item is a named **Plane Kit** (minecart item with plugin data).

### Start the plane

1. Hold the Plane Kit in your main hand.
2. Right-click.
3. Stay within 4 blocks while the engine starts for 4 seconds.
4. The kit is consumed and Plane Mode begins.

Taking damage or moving too far cancels startup, so the plane cannot be used as an instant escape button.

### Fly

- **Look + movement:** steer using normal Minecraft flight controls.
- **Keep moving:** throttle gradually increases.
- **Move backward:** throttle drops.
- **Crouch:** strong braking.
- **Crouch near the ground at low throttle:** land safely and get the Plane Kit back.
- **/plane land:** alternate low-and-slow landing command.

The plugin uses Minecraft's native permitted-flight state rather than a custom vehicle physics engine, which keeps client compatibility high.

## Damage and crashes

Every plane starts with **100 HP**.

- Collision damage scales with throttle.
- Moderate impacts damage the plane, knock it back, and reduce throttle.
- A collision at or above the configured catastrophic throttle destroys the plane immediately.
- Reaching 0 HP also destroys the plane.
- Destruction produces a dramatic sound/particle explosion but does **not** damage terrain.
- A destroyed plane does not return its kit; the player must craft another one.

The action bar displays current plane HP and throttle while flying.

## Commands

- `/plane status` - show your current plane HP and throttle.
- `/plane land` - land when close to the ground and moving slowly.
- `/plane give [player]` - teacher/admin testing command.
- `/plane reload` - reload tuning values.

Admin permission: `eaglerairplane.admin`

## Build

Requirements: Java 21 + Maven.

```bash
mvn clean package
```

Output:

```
target/EaglerAirplaneMod-1.0.0.jar
```

GitHub Actions also builds the plugin and keeps the classroom-ready JAR at:

```
dist/EaglerAirplaneMod-1.0.0.jar
```

## Server integration

The classroom server selector can load this repository directly from `dist/`. The intended catalog entry is:

```
airplane|Eagler Airplane - Evan + Liam|SMalone16/EaglerAirplaneMod|main|dist/EaglerAirplaneMod-1.0.0.jar|1.0.0|0|EaglerAirplaneMod
```

All flight, collision, startup, sound, and effect tuning is in `src/main/resources/config.yml`.
