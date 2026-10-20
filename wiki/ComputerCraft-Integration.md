# ComputerCraft Integration

With [CC: Tweaked](https://modrinth.com/mod/cc-tweaked) installed, the **Ballistic Computer** works as a peripheral. Scripts can read what the fire control knows: the target, the fire mode, and every gun's firing solution. Use it for status boards, logs, alarms, or a fire direction display for the crew.

CC: Tweaked is optional. Without it, nothing changes.

## Read-only by design

The peripheral **only reports**. Scripts cannot designate targets, lay guns, change the fire mode, or fire. Artillery stays a player-run job: targets come from rangefinders, map tables and navigation tables, and a player gives the order to fire from a console.

## Setup

1. Place a computer (or a wired modem) directly against the **Ballistic Computer** block. The modules in its bank don't expose the peripheral, only the computer itself.
2. Wrap it:

```lua
local fc = peripheral.find("ballistic_computer")
```

The peripheral type is `ballistic_computer`.

## Units

| Quantity | Unit |
|---|---|
| Angles | degrees |
| Times | game ticks (20 per second) |
| Distances and positions | blocks |
| Velocity | blocks per tick |

## Functions

### `isPowered()`
Returns `true` when the computer runs (its network interface turns fast enough). An unpowered computer reports no target and no solutions.

### `hasBankConflict()`
Returns `true` when two Ballistic Computers share one bank. Neither works until one is removed.

### `getModules()`
Returns a list of the modules in the bank:

| Value | Module |
|---|---|
| `"differentiator"` | Differentiator: target lead, servo feed-forward |
| `"integrator"` | Integrator: holds a solution between readings |
| `"correlator"` | Correlator: merges several rangefinders |
| `"fuze_computer"` | Fuze Computer: sets timed fuzes |

### `getFireMode()`
Returns `"direct"` or `"plunging"`, as set by the lever on a Fire Mission Console.

### `getTarget()`
Returns `nil` without a target (or while unpowered or in conflict), otherwise a table:

| Field | Type | Meaning |
|---|---|---|
| `x`, `y`, `z` | number | Target position, world coordinates |
| `source` | string | `"rangefinder"`, `"map_table"` or `"nav_table"` |
| `sigma` | number | Uncertainty of the position, blocks |
| `live` | boolean | `false` while a rangefinder solution is only held between readings (Integrator); map and nav table targets are always live |
| `velocity` | table or nil | `{x, y, z}`, blocks per tick, only while tracking a moving target |

### `getGuns()`
Returns a list with one entry per gun reporting to the computer:

| Field | Type | Meaning |
|---|---|---|
| `servo` | table | `{x, y, z}` of the reporting Cannon Servo |
| `mount` | table or nil | `{x, y, z}` of the cannon mount. Nil for a yaw servo whose mount has no pitch servo |
| `name` | string or nil | The name given to the servo, if any |
| `autocannon` | boolean | `true` for autocannons |
| `valid` | boolean | Whether it has a firing solution |
| `reason` | string | `"ok"`, or why there is no solution (see below) |
| `onTarget` | boolean | Laid on its solution: it would fire on the next order |
| `danger` | string or nil | Why a shot would need confirming (see below) |

With a solution (`valid` is `true`), also:

| Field | Type | Meaning |
|---|---|---|
| `pitch`, `yaw` | number | The laying angles of the solution |
| `timeOfFlight` | number | Ticks from firing to impact |
| `range` | number | Distance to the target |
| `fuze` | number | Timed fuze setting, ticks |

Positions of servos and mounts on a ship are the ship's own block coordinates, as other peripherals on that ship see them. The target is always in world coordinates.

#### `reason` values

| Value | Meaning |
|---|---|
| `ok` | Solution found |
| `no_target` | No target designated |
| `no_shell` | No shell loaded |
| `not_assembled` | Mount not assembled, as while a big cannon reloads |
| `out_of_range` | Too far |
| `too_close` | Plunging fire: even the steepest shot lands beyond the target |
| `out_of_arc` | Target outside the gun's arc |
| `round_expires` | The round expires before impact |
| `insufficient_charge` | Not enough propellant |
| `no_orientation` | Ship gun without orientation sensors |
| `no_altitude` | Ship gun without an Altitude Sensor, target well above or below |
| `redundant_drives` | Too many drives aim this gun |
| `no_convergence` | The calculation did not converge |
| `degenerate` | Invalid geometry |

#### `danger` values

| Value | Meaning |
|---|---|
| `safe_zone` | The shell would land in or near a safe zone drawn on a map table |
| `own_side` | The shell would land near our own equipment or hull |
| `friendly` | The shell would land near a spotter carrying a plotted map |

## Examples

### Print the solutions

```lua
local fc = peripheral.find("ballistic_computer")
for _, gun in ipairs(fc.getGuns()) do
  local label = gun.name or "unnamed"
  if gun.valid then
    print(string.format("%s: pitch %.1f, yaw %.1f, %.0f ticks%s",
      label, gun.pitch, gun.yaw, gun.timeOfFlight, gun.onTarget and ", ON TARGET" or ""))
  else
    print(label .. ": " .. gun.reason)
  end
end
```

### Show the target on a monitor

```lua
local fc = peripheral.find("ballistic_computer")
local monitor = peripheral.find("monitor")
monitor.setTextScale(0.5)

while true do
  monitor.clear()
  monitor.setCursorPos(1, 1)
  local t = fc.getTarget()
  if t then
    monitor.write(string.format("Target %.0f %.0f %.0f (%s)", t.x, t.y, t.z, t.source))
  else
    monitor.write("No target")
  end
  sleep(1)
end
```

### Sound an alarm when a shot would be danger close

```lua
local fc = peripheral.find("ballistic_computer")
local speaker = peripheral.find("speaker")

while true do
  for _, gun in ipairs(fc.getGuns()) do
    if gun.danger and gun.onTarget then
      speaker.playNote("bell", 3, 6)
      break
    end
  end
  sleep(1)
end
```

### Log every new target

```lua
local fc = peripheral.find("ballistic_computer")
local last

while true do
  local t = fc.getTarget()
  local key = t and string.format("%.0f,%.0f,%.0f", t.x, t.y, t.z)
  if key ~= last and key then
    local log = fs.open("targets.log", "a")
    log.writeLine(os.date("%H:%M:%S") .. " " .. key .. " " .. t.source)
    log.close()
  end
  last = key
  sleep(1)
end
```

## Notes

- The computer polls its network once per interval, set by the speed of its interface. Values change at that pace, so reading more often than every few ticks gains nothing.
- Only the Ballistic Computer is a peripheral. Simulated's own blocks (sensors, navigation table, swivel bearings) have their own CC peripherals.