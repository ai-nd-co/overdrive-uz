# JS Automation Engine - Design (Overdrive base)

A user-scriptable scenario engine for the Overdrive BYD app: write small JavaScript
scenarios that react to triggers (vehicle wakes up, manual activate, door, charge, ...)
and perform vehicle actions (lock, close windows, flash, climate, ...). This is the one
capability Overdrive does not already have, and it is our differentiator.

Status: DESIGN. Implementation (step 3) starts after the app builds green (step 1).

## Why this fits Overdrive cleanly

Overdrive already has the two halves we need; the engine just bridges them.

| Half | Existing component | We reuse it for |
|------|--------------------|-----------------|
| Triggers (events in) | `monitor/AccMonitor` (ACC on/off = vehicle wake/sleep), `telegram/event/TelegramEventBus` (typed pub/sub with a background dispatch thread), door/charge notifiers, `monitor/BatteryPowerMonitor`, `byd/BydEventClient` | Normalized trigger stream |
| Actions (effects out) | `byd/routing/VehicleCommandRouter` (LockCommand, UnlockCommand, FlashLightsCommand, ClimateOn/Off, CloseAllWindowsCommand, Sunroof, Sunshade, BatteryHeat, ... each with PIN gating, rate limiting, cloud/SDK dual path) | Safe JS action API |
| Host + persistence | `daemon/AccSentryDaemon` (shell-UID immortal daemon), `server/HttpServer` + `assets/web` UI, on-disk + H2 storage | Run the engine, edit + store scenarios |

All actuation goes through `VehicleCommandRouter`, never raw binder, so JS inherits the
existing PIN, rate-limit, dual-path, and Outcome semantics for free.

## Architecture

```
trigger sources            TriggerBus (new)         ScriptEngine (new, Rhino)        actions
-----------------          ----------------         -------------------------        -------
AccMonitor (ACC on/off) -> normalize to     ->  eval user scenario in a        ->  VehicleCommandRouter
TelegramEventBus            typed Trigger        sandboxed scope, time-boxed,        (lock / windows-close /
door/charge notifiers       {type,payload}       with a curated JS API               flash / climate / ...)
BatteryPowerMonitor                                                                  notify() / log()
manual "activate"                                  reads via data bridges
```

- **TriggerBus** (new, thin): subscribes to the existing sources and emits one typed
  `Trigger {type, payload, ts}` stream. MVP trigger types:
  - `vehicle.wake` / `vehicle.sleep`  <- AccMonitor ACC on/off (the headline trigger)
  - `app.activate`                    <- manual (UI button / REST / telegram command)
  - later: `door.open|close`, `charge.start|stop`, `battery.low`, `motion`, `schedule`
  - Backbone: reuse `TelegramEventBus` dispatch (or a sibling `AutomationEventBus`) so we
    do not add a second threading model.

- **ScriptEngine** (new): Mozilla **Rhino** (`org.mozilla:rhino`). Pure-Java, no NDK
  (the native build is already heavy; Rhino keeps build impact to one ~1.5 MB jar).
  - Sandboxed via a `ClassShutter` that denies all Java class access from JS (no
    reflection, no IO, no `java.*`). JS sees only the curated API below.
  - Each scenario runs in a short-lived scope, **time-boxed** (instruction-count
    observer kills runaway/infinite scripts), with a per-scenario rate cap.

- **Automation API (JS globals)** exposed to scenarios:
  - registration: `on(triggerType, handler)` and/or declarative
    `scenario({ when: "vehicle.wake", run: function(ctx){ ... } })`
  - actions (map 1:1 to existing VehicleCommand): `vehicle.lock()`, `vehicle.unlock()`,
    `vehicle.closeWindows()`, `vehicle.flash()`, `vehicle.climateOn()`, `vehicle.climateOff()`,
    `vehicle.sunroof(pct)`, `vehicle.sunshade(pct)`, `vehicle.batteryHeat()`
  - state reads (via existing data bridges): `state.accOn`, `state.soc`, `state.speed`,
    `state.doors`, `state.locked`, `state.charging`
  - utility: `notify(msg)` (telegram/notification), `log(...)`, bounded `sleep(ms)`,
    `every(ms, fn)` (capped)
  - every action returns the router `CommandResult` (outcome/path/latency) so scripts can
    branch on success.

- **Scenario store**: JS source files on disk under the daemon's working dir
  (e.g. `/data/local/tmp/overdrive/scenarios/*.js`) plus a small metadata index
  (enabled flag, name, trigger). CRUD via a new `server/AutomationApiHandler` (REST) and a
  web editor page added under `assets/web`. Survives power cycles like the rest of the
  daemon state.

- **Engine host**: the engine runs inside `AccSentryDaemon` (shell-UID), so it has the
  vehicle bus and survives suspend/cold-boot exactly like the recorder and tailscaled.

## Safety (hard rules baked in)

- All actuation via `VehicleCommandRouter` -> keeps PIN gate, rate limit, dual-path.
- **Trunk is NOT exposed** to the JS API (no `vehicle.trunkOpen`), even though the router
  has the command. No space behind the vehicle; owner rule.
- Sandbox denies filesystem / network / reflection from JS; only the curated API is reachable.
- Per-scenario time cap + action rate cap; respect existing climate/window interlocks.
- **Dry-run mode** + an audit log of every action a scenario takes (what, when, outcome).
- Scenarios are disabled by default on create; user explicitly enables.

## Build impact

- Add `org.mozilla:rhino` to `app/build.gradle.kts` (pure-Java, no native). Nothing else.

## Implementation phases (step 3)

- **P0** - app builds green (in progress, separate task).
- **P1 (core, minimal)** - `TriggerBus` + `ScriptEngine` (Rhino, sandboxed, time-boxed) +
  minimal API (`on`, `vehicle.lock/closeWindows/flash/climateOn`, `state.accOn`, `notify`,
  `log`), hosted in `AccSentryDaemon`. Two triggers: `vehicle.wake`, `app.activate`.
  Load scenarios from disk. Unit tests: engine eval, sandbox denial, trigger dispatch,
  action -> router mapping (mock router).
- **P2 (manage + edit)** - `AutomationApiHandler` REST CRUD + a web editor page in
  `assets/web`; enable/disable; audit log; dry-run toggle.
- **P3 (breadth)** - more triggers (door / charge / battery.low / schedule) and more actions
  (sunroof, sunshade, climate params, AVM snapshot); richer `state`.

## Example scenario (target DX)

```javascript
// Close up and lock when I walk away (vehicle goes to sleep).
scenario({
  when: "vehicle.sleep",
  run: function (ctx) {
    vehicle.closeWindows();
    vehicle.lock();
    notify("Closed windows and locked after ACC off");
  }
});

// Pre-cool when I remotely wake the car on a hot day.
scenario({
  when: "vehicle.wake",
  run: function (ctx) {
    if (state.soc > 30) {
      vehicle.climateOn();
      notify("Pre-cooling; SOC " + state.soc + "%");
    }
  }
});
```
