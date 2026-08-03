# Mila Air Integration for Hubitat (Unofficial)

A parent app + child driver pair that brings Mila air purifiers into Hubitat.

| File | Type | Purpose |
|---|---|---|
| [MilaAirIntegration.groovy](MilaAirIntegration.groovy) | App | Login, device discovery/selection, polling, all API calls |
| [MilaAirPurifier.groovy](MilaAirPurifier.groovy) | Driver | One child device per Mila unit |

Namespace for both files is `vision9074`. If you change it, change it in *both*
(`definition(namespace:)` in each, plus `DRIVER_NAMESPACE` in the app).

---

## API notes

Four things about Mila's API shape the design here, and each of them is easy to
get wrong — older write-ups about this API are misleading on the first two.

1. **It is GraphQL, at `https://graph-api.milacares.com/graphql`.** Circulating
   reverse-engineering notes from 2022 describe REST endpoints under
   `https://api.milacares.com/mms` (`/appliances/meta`, `/sensor/appliance`,
   `/appliance/{code}/command/...`). Those are legacy. The current app and both
   maintained reference implementations use GraphQL. Verified against
   `milasdk/const.py` and the `mila_schema.gql` shipped with `milasdk 2026.1.3`.

2. **Fan speed is set as a percentage, but reported as RPM.** The mutation is
   `applyRoomManualMode(roomId, fanSpeed: Int, targetAqi: Int!)`, and the schema
   documents `fanSpeed` as **0–100**, with `null` meaning "turn the fan off".
   RPM (roughly 600–2000) only ever appears in the opposite direction, via the
   `FanSpeed` sensor reading. Sending RPM where a percentage is expected is the
   easy mistake.

3. **Speed and Automagic are room properties; smart modes are appliance
   properties.** `applyRoomManualMode` and `applyRoomAutomagicMode` take a
   `roomId`. Quiet, sleep, turndown, whitenoise, housekeeper, quarantine, power
   saver and child lock take a `MacAddress` appliance id. The app tracks both
   ids per device and refreshes the room mapping on every poll, since rooms can
   be reassigned in the Mila app.

4. **Auth is a Keycloak direct access grant.** `grant_type=password` against
   realm `prod`, client `prod-ui`, scope `email profile` — the same thing
   `milasdk` does via oauthlib's `LegacyApplicationClient`. No browser redirect
   is involved, which is what makes this workable on Hubitat at all.

The consequence for structure: because one GraphQL query returns every sensor
for every appliance, credentials, tokens and polling all belong in the app
rather than being duplicated per device. A driver-only integration would mean
one login and an N-requests-per-sensor poll loop per purifier.

---

## Install

Both files carry an `importUrl`, so you can use **Import** and paste the raw URL
instead of the code. Install the **driver first** — the app needs it to exist
before it can create devices.

1. **Drivers → Add driver → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirPurifier.groovy`
   then Save.
2. **Apps code → Add app → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirIntegration.groovy`
   then Save.
3. **Apps → Add user app → Mila Air Integration**.
4. Open **Mila account**, enter your Mila email and password, press **Connect
   to Mila**. You should see "Connected successfully".
5. Open **Add / remove Mila devices**, tick the units you want, press
   **Create / Remove Devices**.
6. Back on the main page, set the poll interval and press **Done**.

### If login fails

* `invalid_grant` — the email or password is wrong.
* `unauthorized_client` — Mila has disabled direct password login for the
  `prod-ui` client. Nothing in this integration can work around that; it would
  need the full PKCE browser flow, which Hubitat cannot complete (the redirect
  target is `milacares://`, not HTTP).

---

## What the driver exposes

**Capabilities:** Actuator, Sensor, Refresh, Switch, SwitchLevel, FanControl,
AirQuality, TemperatureMeasurement, RelativeHumidityMeasurement,
CarbonDioxideMeasurement, FilterStatus, SignalStrength.

**Attributes**

| Attribute | Unit | Notes |
|---|---|---|
| `airQualityIndex` | 0–500 | AirQuality capability |
| `pm1`, `pm25`, `pm10` | µg/m³ | |
| `voc` | ppb | multiply by 3.767 for µg/m³ |
| `carbonMonoxide` | ppm | numeric, not the detector enum |
| `carbonDioxide` | ppm | |
| `temperature` | °C/°F | converted to the hub's scale |
| `humidity` | % | |
| `airChangesPerHour` | cph | |
| `timeToClean` | min | |
| `level`, `speed`, `switch` | | standard fan/dimmer attributes |
| `fanSpeedRpm` | rpm | as reported by Mila |
| `mode` | | `Automagic`, `Manual`, `Sleep`, `Quiet`, `Turndown`, `WhiteNoise`, `Housekeeper`, `DeepClean`, `Quarantine`, `Safeguard`, `PowerSaver`, or `offline` |
| `activeModes` | JSON | every mode currently applied |
| `connectionStatus` | | `online` / `offline` |
| `filterStatus`, `filterKind`, `filterDaysLeft`, `filterInstalled` | | |
| `rssi` | dBm | Wi-Fi signal |
| `quietMode`, `sleepMode`, `turndownMode`, `whitenoiseMode`, `housekeeperMode`, `quarantineMode`, `powerSaverMode`, `childLock` | on/off | |
| `soundsConfig`, `bedtimeStart`, `bedtimeEnd`, `roomName`, `firmware`, `lastUpdate` | | |

**Commands beyond the standard capabilities:** `setAutomagicMode`,
`setManualMode`, `setQuietMode`, `setSleepMode`, `setTurndownMode`,
`setWhitenoiseMode`, `setHousekeeperMode`, `setQuarantineMode`,
`setPowerSaverMode`, `setChildLock`, `setSoundsConfig`, `setBedtime`,
`startDeepClean`, `calibrateFilter`, `resetSensor`.

### Switch / level semantics

* `off()` → manual mode with a null fan speed (Mila's way of stopping the fan).
* `on()` → manual mode at the last non-zero level, or the *Default on level*
  preference if there has never been one.
* `setSpeed("auto")` → Automagic.
* Reported RPM is converted back to a percentage using the *Minimum/Maximum fan
  RPM* preferences (default 500/2000) and snapped to steps of 10, matching the
  Mila app's own slider. Adjust those two preferences if your unit's idle RPM
  makes 0%/100% land in the wrong place.

---

## Design notes

* **One poll covers every device.** The app issues a single GraphQL query per
  cycle regardless of how many purifiers you have, then pushes results into each
  child. Shortening the interval does not multiply with device count.
* **Token handling.** Access token is renewed 60 s before expiry, using the
  refresh token when it is still good and falling back to a full password login
  otherwise. A 401 mid-flight triggers one re-auth and retry.
* **Rate limiting.** A 429 from Mila starts a 60 s backoff during which
  scheduled polls are skipped. User-initiated commands still go through.
* **Schema drift guard.** The poll asks for an extended field set including
  `smartModes` and `filter.daysLeft`. Those exist in the published schema but
  are not exercised by ha-mila, so if Mila's live schema rejects them the app
  logs a warning, falls back permanently to a reduced known-good field set, and
  keeps polling. Saving the app re-tries the extended set.
* **Deletion safety.** Unticking a device removes it, but a device that Mila
  did *not* return in the last scan is never auto-deleted — a transient API
  failure should not destroy your device history.
* All 21 GraphQL documents the app can build were validated against
  `mila_schema.gql` from `milasdk 2026.1.3`.

---

## Known limitations

* **Rooms, not appliances, own the fan speed.** If two Mila units share a room
  in the Mila app, changing the speed on one changes both. Split them into
  separate rooms in the Mila app if you need independent control.
* **Unofficial API.** Mila can change or break this without notice.
* **Cloud polling only.** There is no push/websocket channel, so state changes
  made in the Mila app appear at the next poll. Commands issued from Hubitat
  schedule a catch-up refresh ~6 s later.
* **Outdoor air quality and pollen** are available from the same API
  (`Location.outdoorStation`, `Location.pollenStation`) but are not exposed —
  they belong to a location, not a purifier, so they would need a second driver.
* `filterStatus` reports `replace` at ≤ 7 days left; it only populates when the
  extended field set is in use.

## Credits

Reverse engineering and reference implementations by Harshit Sanghvi and
@simbaja: [ha-mila](https://github.com/sanghviharshit/ha-mila),
[milasdk](https://pypi.org/project/milasdk/). MIT licensed, as is this code.
