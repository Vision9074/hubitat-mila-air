# Mila Air Integration for Hubitat

Bring your [Mila](https://milacares.com) air purifiers into Hubitat: full air
quality sensing, fan control, Mila's smart modes, and filter replacement
reminders that the Mila app itself doesn't offer.

> **Unofficial.** This is not made or supported by Mila. It uses the same
> private cloud API as the Mila mobile app, which Mila can change or withdraw at
> any time without notice.

---

## What you get

For each Mila unit on your account, one Hubitat device exposing:

* **Air quality** — AQI, PM1, PM2.5, PM10, VOC, CO, CO₂
* **Environment** — temperature, humidity, air changes per hour, time to clean
* **Fan control** — on/off, 0–100% speed, named speeds, and Automagic (auto) mode
* **Smart modes** — Quiet, Sleep, Turndown, White Noise, Housekeeper,
  Quarantine, Power Saver, Child Lock
* **Filter tracking** — run hours, days remaining, replacement due date, and
  optional reminders to your phone
* **Diagnostics** — Wi-Fi signal, firmware version, online/offline status

Everything is a standard Hubitat attribute, so it all works in Rule Machine,
dashboards, and any other app.

## Requirements

* Hubitat hub on firmware **2.3.0** or later
* A Mila account with at least one purifier already set up in the Mila app
* Your Mila email and password (used only to log in to Mila's own server)

---

## Install

### Option A — Hubitat Package Manager (recommended)

HPM installs both files in the right order and handles updates afterwards.

1. Open **Hubitat Package Manager**.
2. Choose **Install → Search by Keywords**.
3. Search for `mila`, pick **Mila Air Integration**, and press **Next**.
4. Confirm at the prompt to complete the installation.

### Option B — manual import

Install the **driver first** — the app needs it to exist before it can create
devices.

1. **Drivers → Add driver → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirPurifier.groovy`
   then Save.
2. **Apps code → Add app → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirIntegration.groovy`
   then Save.

### Set it up

Once the app and driver are installed, by either method:

1. **Apps → Add user app → Mila Air Integration**.
2. Open **Mila account**, enter your Mila email and password, and press
   **Connect to Mila**. You should see "Connected successfully".
3. Open **Add / remove Mila devices**, tick the units you want, and press
   **Create / Remove Devices**.
4. Back on the main page, set your poll interval and press **Done**.

---

## Filter reminders

Mila records when each filter was fitted, but its app never tells you when to
change one. This integration works out a due date and can remind you.

Set **Replace filter after** in each device's preferences — it's per device, so
a purifier in a dustier room can be set shorter. The default is 6 months, which
is [Mila's own recommendation][mila-filters]; in practice filters last 6–9
months depending on your air.

To get notified, open the app's **Filter change reminders** section and pick any
Hubitat notification device. You'll get one reminder when the filter is
approaching its due date (14 days ahead by default) and one when it falls due.
Fitting a new filter re-arms both automatically. Leave the list empty and
nothing is sent — the attributes are still there for your own rules.

A filter is flagged for replacement when *either* your chosen interval runs out
*or* Mila's own estimate drops to a week left. The two measure different things:
yours is calendar time since installation, Mila's responds to how dirty your air
has actually been. Both are shown separately so you can see which one fired.

`filterHours` counts how long the fan has actually been running. It's estimated
from polling rather than read from the device, so treat it as approximate — and
it can't see any period your hub was offline.

[mila-filters]: https://help.milacares.com/filters.html

---

## Device reference

**Capabilities:** Actuator, Sensor, Refresh, Switch, SwitchLevel, FanControl,
AirQuality, TemperatureMeasurement, RelativeHumidityMeasurement,
CarbonDioxideMeasurement, FilterStatus, SignalStrength.

### Attributes

| Attribute | Unit | Notes |
|---|---|---|
| `airQualityIndex` | 0–500 | |
| `pm1`, `pm25`, `pm10` | µg/m³ | |
| `voc` | ppb | multiply by 3.767 for µg/m³ |
| `carbonMonoxide` | ppm | numeric, not the detector enum |
| `carbonDioxide` | ppm | |
| `temperature` | °C/°F | converted to your hub's scale |
| `humidity` | % | |
| `airChangesPerHour` | cph | |
| `timeToClean` | min | |
| `level`, `speed`, `switch` | | standard fan/dimmer attributes |
| `fanSpeedRpm` | rpm | as reported by Mila |
| `targetAqi` | 0–500 | air quality Mila aims for in manual mode |
| `mode` | | `Automagic`, `Manual`, `Sleep`, `Quiet`, `Turndown`, `WhiteNoise`, `Housekeeper`, `DeepClean`, `Quarantine`, `Safeguard`, `PowerSaver`, or `offline` |
| `activeModes` | JSON | every mode currently applied |
| `connectionStatus` | | `online` / `offline` |
| `filterStatus` | | `normal` / `replace` |
| `filterKind`, `filterInstalled` | | filter type and install date |
| `filterDaysLeft` | days | Mila's own estimate |
| `filterHours` | h | fan run hours since the filter was fitted |
| `filterDaysInService`, `filterDaysRemaining` | days | |
| `filterLifeRemaining` | % | |
| `filterChangeDue` | | date the filter falls due |
| `rssi` | dBm | Wi-Fi signal |
| `quietMode`, `sleepMode`, `turndownMode`, `whitenoiseMode`, `housekeeperMode`, `quarantineMode`, `powerSaverMode`, `childLock` | on/off | |
| `soundsConfig`, `bedtimeStart`, `bedtimeEnd`, `roomName`, `firmware`, `lastUpdate` | | |

### Commands

Beyond the standard capability commands: `setAutomagicMode`, `setManualMode`,
`setQuietMode`, `setSleepMode`, `setTurndownMode`, `setWhitenoiseMode`,
`setHousekeeperMode`, `setQuarantineMode`, `setPowerSaverMode`, `setChildLock`,
`setSoundsConfig`, `setBedtime`, `startDeepClean`, `calibrateFilter`,
`resetFilterTracking`, `resetSensor`.

`resetFilterTracking` restarts filter life from today, for a filter you changed
without recording it in the Mila app. It only affects Hubitat and sends nothing
to Mila.

### Fan behaviour

* **Off** stops the fan; **On** resumes your last speed, or the *Default on
  level* preference if there isn't one.
* `setSpeed("auto")` switches to Automagic.
* Speed percentages snap to steps of 10, matching the Mila app's own slider.
  Mila reports fan speed in RPM, which is converted back using the
  *Minimum/Maximum fan RPM* preferences (default 600/2000 — the values Mila's
  API documents). If your unit idles nearer 500 RPM you can lower the minimum,
  at the cost of the low end of the scale reading about one step high.

---

## Troubleshooting

**Login fails with `invalid_grant`** — the email or password is wrong.

**Login fails with `unauthorized_client`** — Mila has disabled direct password
login for its app client. Nothing in this integration can work around that; it
would require a browser-based sign-in flow that Hubitat cannot complete.

**New attributes don't appear after an update** — Hubitat doesn't refresh a
device when its driver code changes. Press **Refresh** on the device, or wait
for the next poll.

**Values look stale** — everything arrives on the poll interval. Changes made in
the Mila app show up at the next poll; commands sent from Hubitat trigger a
catch-up refresh a few seconds later.

## Limitations

* **Fan speed belongs to the room, not the purifier.** If two Mila units share a
  room in the Mila app, changing the speed of one changes both. Put them in
  separate rooms in the Mila app if you need independent control. Smart modes
  are per unit and unaffected.
* **Cloud polling only.** Mila offers no push channel, so there is always up to
  one poll interval of delay.
* **Outdoor air quality and pollen** are available from Mila's API but not
  exposed here — they belong to a location rather than a purifier, so they would
  need a separate device.
* **Unofficial API**, which may break without warning.

---

## How it works

The app owns the Mila connection for the whole hub — credentials, tokens,
discovery and polling — and the driver holds none of it. That split matters
because a single API query returns every sensor for every appliance, so one poll
serves all your purifiers no matter how many you have. Shortening the interval
doesn't multiply with device count.

A few details worth knowing if you're reading the code:

* **Authentication** is a Keycloak password grant against Mila's own login
  server. Tokens are renewed shortly before expiry, falling back to a full login
  if the refresh token has lapsed, and a mid-flight rejection triggers one
  re-authentication and retry.
* **Rate limiting** is respected: if Mila returns HTTP 429 the app backs off and
  skips scheduled polls for a minute. Commands you trigger still go through.
* **Schema changes** are survivable. The poll requests a richer field set than
  strictly required; if Mila's API ever rejects part of it, the app logs a
  warning, falls back to a reduced known-good query and keeps working.
* **Devices are never auto-deleted** because of an API hiccup. Unticking a
  device in the app removes it, but a device simply missing from a scan is left
  alone along with its history.

## Contributing

Issues and pull requests are welcome. If you fork this and change the
`vision9074` namespace, change it in *both* files — `definition(namespace:)` in
each, plus `DRIVER_NAMESPACE` in the app — or the app won't be able to create
devices.

## Credits

Built on the reverse engineering and reference implementations of Harshit
Sanghvi and @simbaja: [ha-mila](https://github.com/sanghviharshit/ha-mila) and
[milasdk](https://pypi.org/project/milasdk/).

## License

[MIT](LICENSE).
