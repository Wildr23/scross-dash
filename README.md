# S-Cross Dash (`scross-dash`)

**S-Cross Dash** (`ua.odesa.scrossdash`) is a native Android & Android Auto OBD-II telemetry cockpit and trip analytics companion built with **Kotlin**, **Jetpack Compose**, and the **AndroidX Car App Library**, tailored for the **2024 Suzuki S-Cross 1.4 BoosterJet 48V Mild Hybrid (`K14D`)**.

---

## Target Vehicle & Hardware

* **Vehicle:** 2024 Suzuki S-Cross, 1.4 BoosterJet Mild Hybrid (`K14D` engine, 6-speed automatic `6AT`, `48V` ISG starter-generator, `47 L` fuel tank).
* **OBD-II Adapter:** ELM327 v1.5 Bluetooth (`PIC18F25K80`) over **Classic Bluetooth SPP** (`UUID: 00001101-0000-1000-8000-00805F9B34FB`) using `ISO 15765-4 CAN 11/500` (`ATSP6`).
* **UI Language:** Ukrainian (`uk`) across both Android Auto and the phone companion app.

---

## Key Features

### 1. Android Auto Dashboard (`NavigationTemplate` + `Surface`)
Designed for high glanceability on 7" (`800x480`) head units in both **Coolwalk Split-Screen** and **Fullscreen** modes:
* **Coolwalk Split-Screen & Full-Height Layout (`SCrossDashboardScreen`, `BentoCanvasRenderer`):**
  * Strictly clamps horizontal bounds (`left`, `right`) and `bottom` to `visibleArea` in Split-Screen while capping the top inset (`minOf(area.top, 14f)`) so the compact `ActionStrip` never squishes the `2x2` Bento grid.
  * Single compact `ActionStrip` control to cycle theme (`Авто -> Ніч -> День`), with automatic sunset/sunrise dark mode via `SolarTwilightHelper` (`carContext.isDarkMode || SolarTwilightHelper.isNightNow()`).
  * Smooth 60 fps (`200 ms` animated) tap-to-expand detail overlays on all 4 cards with tap-anywhere dismissal.
* **4 Glanceable Bento Cards:**
  1. **Витрата та бак:** Large hero Trip Average Consumption (`л/100`), top-right live instant consumption badge (`л/г` stopped / `л/100` moving), remaining tank & estimated range (`Бак XX.X л • ~XXX км`), bottom micro fuel gauge, and a read-only **"На АЗС"** overlay (`Долити ~XX.X л` and UAH estimate).
  2. **Поїздка та 48V:** Trip distance (`км`), trip fuel cost (`грн`), live 48V status pill (`⚡ ПІДХВАТ`, `🔋 РЕКУП`, `🛑 СТОП`, `ДВЗ`), cumulative 48V fuel/money savings (`+мл / +л` & `грн`), and an expanded trip overlay with trip reset.
  3. **Оцінка водіння (Eco `0 -> 100`):** Toyota-style accumulative trip Eco Score combining acceleration smoothness, regen/coasting braking, and speed steadiness.
  4. **Бережливість до авто (Care `0 -> 100`):** Cold-start warm-up monitor (`< 70°C`), Anti-LSPI low-RPM high-boost protection, thermal/12V health monitoring, and post-boost turbo cool-down guidance (scoring freezes cleanly when `rpm < 300`).

### 2. Zero-Touch Fuel Tank & Real-Car Calibrated Math
* **MAF Fuel Rate (`TelemetryEngine`):** Computed from `PID 0x10` (MAF) and `PID 0x44` (Lambda, fallback `1.0`) using:
  $$\text{FuelRate}_{\text{L/h}} = \frac{\text{MAF} \times 3600}{14.7 \times \lambda \times 745} \times K_{\text{cal}}$$
  with default `mafCalibration = 1.00f` (verified within ~1.3% of the Suzuki S-Cross onboard trip computer on road tests).
* **Anti-Slosh `PID 0x2F` Anchoring:** Uses a 10-sample rolling median filter and maps S-Cross OEM float sensor saturation (`>= 93.0% -> 47.0 L`). Anchors only when stationary uncalibrated, once cleanly at trip start (`tripDistanceKm < 0.05f`), or during a genuine stationary refuel (`speedKmh == 0` for `>= 25 s` and upward delta `> 6.0 L`), avoiding false jumps on sloped traffic lights.

### 3. Phone Companion App (Jetpack Compose, Light & Dark Themes)
* **Adaptive Light & Dark Theme (`PhoneTheme`):** Supports `AUTO` (follows `isSystemInDarkTheme()`), `LIGHT`, and `DARK` modes.
* **Tab 1 — `Панель` (`PhoneDashboardScreen`):**
  * Live connection & telemetry overview, paired Bluetooth ELM327 device selector, and realistic **Demo Mode** for bench/DHU testing without a vehicle.
  * Fuel price (`грн/л`), MAF calibration multiplier, compact tank calibration (`47 л` / `Зчитати з датчика`), theme mode selector, opt-in headlights reminder toggle (off by default), and OBD-II DTC scanner & clearer (`Mode 03` / `Mode 04` supporting multi-ECU `7E8`/`7E9` responses).
* **Tab 2 — `Аналітика` (`TripAnalyticsTab`):**
  * Period filters (`Тиждень`, `Місяць`, `Рік`, `Весь час`) and KPI summary (distance, fuel burned, total spend, `грн/км`, weighted `л/100км`, 48V savings, average Eco & Care scores).
  * 3 custom Compose `Canvas` charts with zero baseline & `rememberTextMeasurer()` axis labels (consumption dynamics, fuel spend vs 48V savings, Eco/Care trend).
  * SQLite trip archive (`TripDatabaseHelper` v2) with single-row upsert per trip, deletion confirmations, per-trip `.csv` export, and full bulk `.zip` export (`LogExporter`).

### 4. Background Telemetry Service & Lifecycle
* **`ObdTelemetryService` & `CarBluetoothReceiver`:** Driven by Android Auto `CarConnection` state and Suzuki/AAWireless Bluetooth ACL events to collect OBD-II telemetry cleanly in the background.
* **Ignition-Off Finalization & Silent Summary (`TripNotificationHelper`):** Detects ECU sleep/ignition-off (`NO DATA`, `STOPPED`, etc. or low voltage), finalizes the trip in SQLite, closes and rotates CSV logs (`TelemetryCsvLogger`, keeping up to `50` files), resets active trip metrics to `0.0 km`, and posts a silent arrival summary notification on channel `Підсумки поїздок`.

---

## Repository Structure

```text
app/src/
├── main/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── drawable/ic_launcher.xml
│   │   ├── values/{strings.xml, themes.xml}
│   │   └── xml/{automotive_app_desc.xml, file_paths.xml}
│   └── java/ua/odesa/scrossdash/
│       ├── SCrossApplication.kt           # Singleton dependency graph
│       ├── MainActivity.kt                # Compose host, runtime BT permissions & edge-to-edge theming
│       ├── auto/
│       │   ├── SCrossCarAppService.kt     # Android Auto CarAppService entry point
│       │   ├── SCrossSession.kt           # Car App Session lifecycle & config dark-mode hooks
│       │   ├── SCrossDashboardScreen.kt   # NavigationTemplate, SurfaceCallback & theme ActionStrip
│       │   └── BentoCanvasRenderer.kt     # Hardware-accelerated Canvas renderer & 60fps overlays
│       ├── data/
│       │   ├── SettingsRepository.kt      # SharedPreferences-backed reactive AppSettings & tank liters
│       │   ├── db/
│       │   │   ├── TripDatabaseHelper.kt  # SQLiteOpenHelper (v2) trips schema & single-row upsert
│       │   │   ├── TripRecord.kt          # Immutable trip archive entity
│       │   │   └── TripRepository.kt      # StateFlow trip archive repository
│       │   └── log/
│       │       ├── TelemetryCsvLogger.kt  # Per-session CSV telemetry logger (50-file retention)
│       │       └── LogExporter.kt         # FileProvider CSV / bulk ZIP share exporter
│       ├── domain/
│       │   ├── TelemetryEngine.kt         # Fuel rate, 47L tank fusion, 48V savings, Eco & Care math
│       │   ├── SolarCalculator.kt         # Civil twilight calculation
│       │   └── SolarTwilightHelper.kt     # Auto night-mode resolution for Android Auto
│       ├── model/
│       │   └── TelemetryModels.kt         # RawObdSnapshot, DashboardState, FuelTankInfo, AppSettings
│       ├── obd/
│       │   └── Elm327BluetoothManager.kt  # Classic BT SPP client, ELM327 AT init, PID loop & Demo Mode
│       ├── receiver/
│       │   └── CarBluetoothReceiver.kt    # Suzuki / AAWireless ACL connect/disconnect trigger
│       ├── service/
│       │   ├── ObdTelemetryService.kt     # Foreground OBD service bound to CarConnection lifecycle
│       │   └── TripNotificationHelper.kt  # Silent arrival summary notifications
│       └── ui/
│           ├── PhoneTheme.kt              # Light & Dark Compose color palettes (LocalSCrossColors)
│           ├── PhoneDashboardScreen.kt    # Companion dashboard UI & settings
│           └── TripAnalyticsTab.kt        # Analytics charts, KPIs & SQLite trip archive UI
└── test/java/ua/odesa/scrossdash/
    └── VerificationTest.kt                # Unit tests for DTC parsing, tank fusion, scores, SQLite & CSV
```

---

## Architectural Guardrails

1. **Preserve `NavigationTemplate` + `Surface` (`SCrossDashboardScreen`, `BentoCanvasRenderer`):** Android Auto Coolwalk only grants side-by-side Split-Screen placement alongside media widgets to `NAVIGATION` apps rendering via `NavigationTemplate` and `SurfaceCallback`.
2. **Classic Bluetooth SPP & Half-Duplex ELM327:** Keep Classic SPP (`RFCOMM`) with sequential `ATZ -> ATE0 -> ATL0 -> ATS0 -> ATH0 -> ATAT2 -> ATSP6` initialization (`PIC18F25K80` does not support BLE, and ELM327 requires waiting for the `>` prompt between commands).
3. **Preserve `mafCalibration = 1.00f`:** Empirically calibrated against Suzuki S-Cross 1.4 BoosterJet OEM trip computer readings.
4. **Canvas Allocations:** Pre-allocate `Paint`, `RectF`, and `Path` objects as fields on `BentoCanvasRenderer` rather than allocating inside `draw()`.
5. **Single Source of Truth:** Route telemetry mutations through `TelemetryEngine.state: StateFlow<DashboardState>` so Android Auto, phone UI, SQLite trips, and CSV logs stay consistent.

---

## Build, Test & Install

### Run Unit Tests & Build Debug APK
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

### Install for Android Auto
Android Auto hides sideloaded navigation apps unless installed with `com.android.vending` as the installer package (or Unknown Sources enabled in Android Auto Developer Settings):
```powershell
adb install -r -i "com.android.vending" app\build\outputs\apk\debug\app-debug.apk
```

### Testing Without a Vehicle
1. Open **S-Cross Dash** on your phone and enable **Демо-режим (Demo Mode)**.
2. Connect to Android Auto Desktop Head Unit (`DHU`) to verify Split-Screen, Fullscreen, Light/Dark themes, and tap-to-expand overlays.
