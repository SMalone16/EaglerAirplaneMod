# EaglerAirplaneMod

**Evan + Liam's Plane System v1.1** for the Pawling classroom Eaglercraft/Paper server.

Version 1.1 replaces the original Creative-Flight-based movement with a server-side **fixed-wing flight model**. The goal is not a full flight simulator, but it now behaves much more like an airplane: it needs runway speed to take off, preserves momentum in the air, stalls when too slow, and cannot hover by releasing the keyboard.

## Controls

- **Sprint:** increase throttle.
- **Crouch:** reduce throttle; on the runway it also acts as a wheel brake.
- **Mouse left/right:** command yaw. The physics produces a simulated bank/roll while turning.
- **Mouse up/down:** command pitch.
- **Release the controls:** throttle remains where you left it and the aircraft keeps moving.

At 100% throttle, letting go of the keyboard does **not** stop the aircraft.

## Takeoff

1. Right-click the Plane Kit.
2. Wait through the engine-start sequence.
3. Hold Sprint to increase throttle and accelerate down the runway.
4. The plane remains on the ground until it reaches the configured **takeoff speed**.
5. Once at rotation speed, look upward to rotate and lift off.

Default takeoff speed is **0.48 blocks/tick**. The HUD changes to `ROTATE: LOOK UP` when the aircraft is ready.

If the runway ends before the plane reaches rotation speed, it becomes airborne without enough lift and can stall.

## Airspeed, lift, and stalls

The plugin tracks a real internal airspeed rather than using Minecraft fly speed.

- Engine thrust pushes airspeed toward the speed associated with the current throttle.
- Aerodynamic drag slows the aircraft when throttle is reduced.
- Climbing and banking add drag.
- Lift increases with airspeed.
- Below the default **0.34 stall speed**, lift rapidly disappears and the plane sinks.
- A level aircraft at healthy cruise speed receives enough lift to approximately counter Minecraft gravity.

This means a pilot has to manage **energy**, not just point the camera where they want to go.

## Pitch, yaw, and roll

### Pitch: yes

The player's vertical look angle commands aircraft pitch, limited to a configurable default of ±35°. Pitch changes are smoothed so the velocity vector does not instantly snap to the camera.

### Yaw: yes

Looking left/right commands yaw. The actual aircraft direction follows gradually rather than teleporting to the new heading.

### Roll/bank: physics yes, camera no

A normal Paper server plugin **cannot rotate the player's camera around the forward axis**, so true first-person visual roll is not available without modifying the Eaglercraft client.

The closest server-only approximation is implemented:

- yaw input produces a calculated bank angle,
- bank builds and relaxes gradually,
- bank adds drag and reduces vertical lift,
- the action-bar HUD shows `BANK L/R xx°`,
- the plane's direction responds gradually while turning.

So roll now matters to the flight model even though the Minecraft horizon itself stays level.

## Landing

To make a safe landing:

1. Reduce throttle with Crouch.
2. Approach the ground.
3. Keep airspeed at or below the configured landing speed.
4. Keep the nose reasonably level.
5. Continue holding Crouch near the ground.

A successful landing returns the Plane Kit. `/plane land` can also finish a landing if the same safe conditions are met.

## Damage and crashes

Planes still have **100 HP**.

Collision damage is now based on **actual airspeed**, not throttle. A high-speed collision above the catastrophic-impact threshold destroys the plane immediately. The crash uses dramatic sound/particle effects without destroying terrain.

## Crafting

```
Iron Block | Diamond | Iron Block
Redstone   | Furnace | Redstone
Iron Block | Diamond | Iron Block
```

## Commands

- `/plane status` — flight phase, health, throttle, airspeed, and bank.
- `/plane land` — complete a safe landing.
- `/plane give [player]` — teacher/admin testing command.
- `/plane reload` — reload flight tuning.

Admin permission: `eaglerairplane.admin`

## Build

Requires Java 21 + Maven.

```bash
mvn clean package
```

Output:

```
target/EaglerAirplaneMod-1.1.0.jar
```

GitHub Actions publishes the classroom-ready JAR to:

```
dist/EaglerAirplaneMod-1.1.0.jar
```

The classroom server selector should use:

```
airplane|Eagler Airplane - Evan + Liam|SMalone16/EaglerAirplaneMod|main|dist/EaglerAirplaneMod-1.1.0.jar|1.1.0|0|EaglerAirplaneMod
```

All physics values are exposed in `src/main/resources/config.yml`, so takeoff speed, stall speed, maximum speed, pitch limits, bank response, drag, lift, and landing difficulty can be tuned after playtesting.
