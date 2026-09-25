# Smart Marker Frontend — codebase map

This is the Android tablet app for the Smart Marker. It is a Kotlin + Jetpack Compose application aimed at a OnePlus Pad Go (landscape). The operator loads a site plan, positions the marker, then surveys and projects vertices onto the floor.

The UI was ported from the Industry-themed mock in `UI/`. Product intent lives in `docs/project_overview`. The wire codec is `docs/FRONTEND_PROTOCOL.md`, the tablet copy of the CCU catalog `docs/CMD_PROTOCOL.md`. The field sketch is `docs/data_packets.txt`.

---

## What the app does

| Job | What the operator sees | Status in this codebase |
| --- | --- | --- |
| Plan canvas | Pinch/zoom, drag/pan, tap vertices, FOV wedge, survey / project | Working (device actions simulated) |
| Plans | Switch demo DXFs or import a `.dxf` from the tablet | Working (Kotlin DXF reader) |
| Pose | Begin Tag Finding, nudge the beam, lock two tags | Working (`BEGIN_TAG_SEARCH` / `TAG_FOUND`; pose also follows telemetry `poseValid`) |
| Settings | Units, laser brightness, link, about | Working (link is a UI toggle until serial is wired) |
| USB-C dongle | Talk to the ESP32 NOW dongle (~10 KB/s peak) | Types + USB host hook only |
| Installable APK | Run from Android Studio onto the Pad Go | Working |

Four screens share one `SmartMarkerState`. There is no navigation graph: the side rail sets `state.screen`.

```
MainActivity
  └─ SmartMarkerApp
       ├─ SideNav + TopBar          (always on)
       ├─ Plan / Plans / Pose / Settings
       └─ User menu + connection dialog overlays
              │
              ▼
       SmartMarkerViewModel  ← single source of truth
              │
              ├─ DxfParser          (import)
              ├─ UsbDongleManager   (future serial)
              └─ SharedPreferences  (units, beam, tags, …)
```

---

## Where source lives

All application Kotlin is under:

`app/src/main/java/com/example/smartmarkerfrontend/`

| Folder | Role |
| --- | --- |
| *(package root)* | Process entry (`MainActivity`) and shell layout (`SmartMarkerApp`) |
| `ui/` | ViewModel + screens + chrome + canvas + widgets + theme |
| `data/` | Domain models, demo plan geometry, DXF parser, USB packet types |
| `device/` | USB host helper for the dongle |

Resources (fonts, icons, strings, USB filter, manifest) are under `app/src/main/res/` and `app/src/main/AndroidManifest.xml`.

Design inputs (not compiled into the APK):

| Path | Role |
| --- | --- |
| `UI/Smart Marker.dc.html` | Interactive mock this app was ported from |
| `UI/industry.css` | Colour, type, and blueprint tokens |
| `docs/project_overview` | Product / stack notes |
| `docs/FRONTEND_PROTOCOL.md` | Tablet copy of the CCU command catalog (framing, cmd bytes, payloads) |
| `docs/data_packets.txt` | Field-list sketch of that catalog |
| `docs/WIFI_DONGLE_HOSTING.md` | USB-C ESP-NOW hop, same text as the CCU copy |

---

## Subroutine map

Each row is a behaviour. “Governs” means: change that file first. The ViewModel is listed whenever it owns the state change, even if a screen draws the controls.

### App start and shell

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Process launch, landscape lock, system bars | `MainActivity.kt` | `AndroidManifest.xml` (`sensorLandscape`, USB intent) |
| Theme (light-only Industry colours + Barlow) | `ui/theme/Theme.kt`, `Color.kt`, `Type.kt` | `res/font/barlow_*.ttf`, `res/values/themes.xml` |
| Root layout: nav + header + current screen + dialogs | `SmartMarkerApp.kt` | `ui/chrome/AppChrome.kt` |
| Side rail (Plan / Plans / Pose / Settings) | `AppChrome.kt` → `SideNav` | `SmartMarkerViewModel.go()` |
| Header chips (laser, pose, link, user) | `AppChrome.kt` → `TopBar` | ViewModel `toggleConn`, `toggleUser`, `go(Pose)` |
| User overflow menu | `AppChrome.kt` → `UserMenu` | ViewModel `closeMenus` |
| Connection dialog | `AppChrome.kt` → `ConnectionDialog` | ViewModel `toggleLink`, `closeConn` |
| Buttons, tags, blueprint frames, toggles | `ui/components/Widgets.kt` | `ui/components/SmIcons.kt` |
| App / launcher mark | `res/drawable/ic_smart_marker.xml`, `ic_launcher_*.xml` | `res/mipmap-anydpi-v26/` |

### Plan canvas (pinch, pan, nodes)

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Plan page chrome (toggles, zoom stack, scale bar, survey/project, run overlay) | `ui/screens/PlanScreen.kt` | ViewModel methods below |
| Pointer handling (one-finger pan, two-finger pinch, tap) | `ui/canvas/PlanCanvas.kt` | ViewModel `panBy`, `setView`, `tapCanvas`, `markGestureStart` |
| Draw grid / floor plan / FOV / nodes / labels | `PlanCanvas.kt` (`drawGrid`, `drawPlan`, `drawFov`, `drawNodes`, `drawLabels`) | `data/DemoPlan.kt` geometry, `data/Models.kt` `inMarkerView` |
| First fit of the plan to the tablet | ViewModel `onCanvasSize`, `fitPlan` | `Models.kt` `fitScale` |
| Zoom buttons | ViewModel `zoomBy`, `fitPlan` | `PlanScreen.kt` |
| Select / multi-select vertices | ViewModel `tapNode`, `tapCanvas`, `toggleMulti`, `clearSelection` | `PlanCanvas.kt` hit test (~28 px) |
| Labels / grid / FOV overlays | ViewModel `toggleLabels`, `toggleGrid`, `toggleFov` | `PlanCanvas.kt` |
| Survey selected vertices | ViewModel `startSurvey` | `PlanScreen.kt` overlay; later `TabletCommand.BeginSurvey` |
| Project / halt beam | ViewModel `toggleProject` | `PlanScreen.kt`; later `TabletCommand.BeginProjection` |
| Cancel in-progress survey | ViewModel `cancelRun` | `PlanScreen.kt` `RunOverlay` |
| Keep screen awake while surveying or projecting | `PlanScreen.kt` (`LocalView.keepScreenOn`) | — |
| Default Riverside Plot 14 geometry + V01–V12 | `data/DemoPlan.kt` | Loaded by ViewModel `initialState()` |
| Node status colours and FOV math | `data/Models.kt` (`NodeStatus`, `inMarkerView`, `SmartMarkerState`) | `PlanCanvas.kt`, `PlanScreen.kt` |

World units are metres (x = east, y = north). Screen position is `pan.x + east * scale`, `pan.y - north * scale` so +east is right and +north is up (`SmartMarkerState.worldToScreen`). The settings choice of mm, m, or ft changes only `formatCoord` on the plan card.

### Plans and DXF import

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Plans list UI + “Choose .dxf file…” | `ui/screens/PlansScreen.kt` | SAF `OpenDocument` launcher |
| Open a listed plan (and remember node edits on the previous one) | ViewModel `openPlan` | Sends `TabletCommand.RegisterNodes` into the dongle manager |
| Read a picked `.dxf` and jump to Plan | ViewModel `importDxf` | `data/DxfParser.kt` |
| Parse ASCII DXF entities → vertices + polylines | `DxfParser.extract` | Helpers in the same file: `parseEntities`, `buildNodes`, `mergePoints` |
| Import error banner | ViewModel `dismissImportError` | `PlansScreen.kt` |
| Parser unit test | `app/src/test/.../data/DxfParserTest.kt` | — |

`DxfParser` pulls `POINT`, `INSERT`, compact `CIRCLE` centres, `TEXT`/`MTEXT` labels, `LINE`, `LWPOLYLINE`, and `POLYLINE`. That is the current coordinate extraction path. `docs/project_overview` still names Chaquopy + `ezdxf`; that Python runtime is **not** in the Gradle build yet.

### Pose / positioning

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Pose page (begin / pad / identified) | `ui/screens/PoseScreen.kt` | ViewModel methods below |
| Begin Tag Finding | ViewModel `beginTagSearch` | `TabletCommand.BeginTagSearch` |
| Fine / coarse nudge step | ViewModel `setStep` | Persisted in `SharedPreferences` (`smart_marker`) |
| Choose / add a survey tag | ViewModel `pickTag`, `addTag` | Pad dropdown while searching |
| Confirm current tag | ViewModel `confirmTag` | `TabletCommand.TagFound`; second search starts automatically |
| Nudge beam (az / el) | ViewModel `nudge` | `TabletCommand.NudgeBeamX` / `NudgeBeamY`; FOV heading uses `az` |
| Reposition (clear tag locks, keep origin) | ViewModel `resetPose` | Returns to begin; no wire command |
| Clear origin entirely | ViewModel `clearOrigin` | UI-only; no wire command |
| Continue to plan | ViewModel `go(AppScreen.Plan)` | Shown with Reposition / Clear origin when posed |

Heading used by the FOV wedge is `SmartMarkerState.headingRad` (telemetry `effectiveYaw` when present). Two distinct `TAG_FOUND` ids — or telemetry `poseValid` — mark the site as posed and hide the pad.

### Settings

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Settings layout (units, laser, connection, about) | `ui/screens/SettingsScreen.kt` | `Widgets.kt` `SegButton`, `PillToggle` |
| Plan display units (mm / m / ft) | ViewModel `setUnits` | `Models.kt` `formatCoord` (detail card on Plan). Stored coordinates stay metres. |
| Laser brightness slider | ViewModel `setBeam` | `TabletCommand.SetLaserPower` |
| Auto-reconnect pill | ViewModel `toggleAutoReconnect` | Preferences |
| Disconnect / reconnect | ViewModel `toggleLink` | Same as the header connection dialog |
| About copy (app, firmware, tablet, user) | `res/values/strings.xml` | `SmartMarkerState.firmwareVersion` when USB handshake exists |

### USB dongle (tablet ↔ ESP32)

| Subroutine | Governs | Also involved |
| --- | --- | --- |
| Packet shapes (telemetry in, commands out) | `data/DeviceProtocol.kt` | Spec: `docs/FRONTEND_PROTOCOL.md` |
| USB host device list / VID match | `device/UsbDongleManager.kt` | `res/xml/usb_device_filter.xml` |
| Attach intent on the activity | `AndroidManifest.xml` | Filter lists Silicon Labs, CH340, FTDI, Espressif, Prolific |
| Command send hook | `UsbDongleManager.send` | Called from the ViewModel on survey, project, nudge, tag search, register-nodes, laser |

Until framing is implemented, the UI still **simulates** survey progress, projection, tag lock, and link state so the designed screens can be exercised without hardware.

### Shared state (the “brain”)

Almost every button ends in `ui/SmartMarkerViewModel.kt`. Persistent bits (move step, current tag, units, beam, auto-reconnect) go through `SharedPreferences` named `smart_marker`. Transient UI (selection, pan/zoom, run overlay, menus) stays in `MutableStateFlow<SmartMarkerState>`.

`data/Models.kt` is the schema for that state: screens, nodes, geometry, tags, run progress, user profile, helpers (`inMarkerView`, `fitScale`, `formatCoord`).

---

## File index (Kotlin)

```
app/src/main/java/com/example/smartmarkerfrontend/
  MainActivity.kt                 Launch + orientation
  SmartMarkerApp.kt               Screen switcher

  data/
    Models.kt                     State + FOV / fit / units helpers
    DemoPlan.kt                   Bundled Riverside Plot 14
    DxfParser.kt                  ASCII DXF → nodes + paths
    DeviceProtocol.kt             USB telemetry / command types

  device/
    UsbDongleManager.kt           USB host detection + send stub

  ui/
    SmartMarkerViewModel.kt       All user actions
    canvas/PlanCanvas.kt          Gestures + drawing
    chrome/AppChrome.kt           Nav, header, dialogs
    components/Widgets.kt         Blueprint UI kit
    components/SmIcons.kt         Lucide-style vectors
    screens/PlanScreen.kt
    screens/PlansScreen.kt
    screens/PoseScreen.kt
    screens/SettingsScreen.kt
    theme/Color.kt, Type.kt, Theme.kt
```

---

## Build and run

- Module: `:app` (`applicationId` `com.example.smartmarkerfrontend`)
- Versions: `gradle/libs.versions.toml`, plugins in `app/build.gradle.kts`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Open the project in Android Studio, select the Pad Go, Run. USB debugging must be on. The activity requests landscape.

---

## Not wired yet

1. **Serial protocol on the dongle** — `UsbDongleManager.send` is a no-op beyond detection. Fill this in against `DeviceProtocol` / `docs/FRONTEND_PROTOCOL.md`.
2. **Chaquopy + ezdxf** — documented as the long-term DXF stack; the running importer is `DxfParser`.
3. **Real user / site accounts** — header user is the mock profile (`D. Whitfield`). Switch site / sign out are placeholders.
4. **Firmware update check** — button exists on Settings, no backend.
