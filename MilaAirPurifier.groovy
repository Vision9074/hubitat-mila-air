/**
 *  Mila Air Purifier (Unofficial)
 *  ==============================
 *  Child driver for the "Mila Air Integration" app. It holds no credentials and
 *  makes no network calls of its own: the parent app owns the Mila cloud
 *  connection, polls once for every appliance, and pushes results down here.
 *
 *  Install the app first, then this driver, then add devices from the app.
 *
 *  ---------------------------------------------------------------------------
 *  BEHAVIOUR NOTES
 *  ---------------------------------------------------------------------------
 *  Fan speed / auto mode are ROOM level settings in Mila's API. If two Mila
 *  units share a room in the Mila app, setting the speed on one changes both.
 *  Smart modes (quiet, sleep, child lock, ...) are per appliance.
 *
 *  Mila accepts a fan speed as a percentage 0-100 (0 = minimum, ~600 RPM;
 *  100 = maximum, ~2000 RPM; "off" is sent as a null speed) but *reports* the
 *  running speed as RPM. The reported RPM is mapped back to a percentage using
 *  the Minimum/Maximum fan RPM preferences.
 *
 *  "on" resumes the last non-zero level (or the Default on level). "off" stops
 *  the fan by putting the room into manual mode with no speed.
 *
 *  License: MIT
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(name: "Mila Air Purifier", namespace: "vision9074", author: "vision9074",
               importUrl: "https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirPurifier.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"
        capability "FanControl"
        capability "AirQuality"                      // airQualityIndex
        capability "TemperatureMeasurement"          // temperature
        capability "RelativeHumidityMeasurement"     // humidity
        capability "CarbonDioxideMeasurement"        // carbonDioxide
        capability "FilterStatus"                    // filterStatus
        capability "SignalStrength"                  // rssi, lqi

        // --- Air quality (Mila reports more than the built-in capabilities cover)
        attribute "pm1",  "number"                   // ug/m3
        attribute "pm25", "number"                   // ug/m3
        attribute "pm10", "number"                   // ug/m3
        attribute "voc",  "number"                   // ppb
        attribute "carbonMonoxide", "number"         // ppm (numeric, not the detector enum)

        // --- Operation
        attribute "mode", "string"                   // Mila's actualMode, or "offline"
        attribute "activeModes", "string"            // JSON list of every mode currently applied
        attribute "fanSpeedRpm", "number"
        attribute "targetAqi", "number"
        attribute "airChangesPerHour", "number"      // ACH
        attribute "timeToClean", "number"            // minutes
        attribute "connectionStatus", "enum", ["online", "offline"]
        attribute "lastUpdate", "string"

        // --- Appliance details
        attribute "roomName", "string"
        attribute "firmware", "string"
        attribute "filterKind", "string"
        attribute "filterDaysLeft", "number"
        attribute "filterInstalled", "string"
        attribute "soundsConfig", "enum", ["Enabled", "DaytimeOnly", "Disabled"]
        attribute "bedtimeStart", "string"
        attribute "bedtimeEnd", "string"

        // --- Smart modes
        attribute "quietMode", "enum", ["on", "off"]
        attribute "housekeeperMode", "enum", ["on", "off"]
        attribute "quarantineMode", "enum", ["on", "off"]
        attribute "sleepMode", "enum", ["on", "off"]
        attribute "turndownMode", "enum", ["on", "off"]
        attribute "whitenoiseMode", "enum", ["on", "off"]
        attribute "powerSaverMode", "enum", ["on", "off"]
        attribute "childLock", "enum", ["on", "off"]

        // --- Commands beyond the standard capabilities
        command "setAutomagicMode"
        command "setManualMode", [[name: "Fan speed %*", type: "NUMBER", description: "0-100"]]
        command "setQuietMode",       [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setHousekeeperMode", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setQuarantineMode",  [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setPowerSaverMode",  [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setChildLock",       [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setSleepMode", [
            [name: "State*",   type: "ENUM", constraints: ["on", "off"]],
            [name: "Fan mode", type: "ENUM", constraints: ["Lowest", "Low", "Medium", "High", "Highest"]]
        ]
        command "setTurndownMode", [
            [name: "State*",   type: "ENUM", constraints: ["on", "off"]],
            [name: "Fan mode", type: "ENUM", constraints: ["Lowest", "Low", "Medium", "High", "Highest"]]
        ]
        command "setWhitenoiseMode", [
            [name: "State*",   type: "ENUM", constraints: ["on", "off"]],
            [name: "Fan mode", type: "ENUM", constraints: ["Lowest", "Low", "Medium", "High", "Highest"]]
        ]
        command "setSoundsConfig", [[name: "Sounds*", type: "ENUM", constraints: ["Enabled", "DaytimeOnly", "Disabled"]]]
        command "setBedtime", [
            [name: "Start*", type: "STRING", description: "24h local time, e.g. 22:30"],
            [name: "End*",   type: "STRING", description: "24h local time, e.g. 07:00"]
        ]
        command "startDeepClean",  [[name: "Target ACH*", type: "NUMBER", description: "Air changes per hour to reach"]]
        command "calibrateFilter"
        command "resetSensor", [[name: "Sensor*", type: "ENUM", constraints: ["Co", "Voc"]]]
    }

    preferences {
        input name: "targetAqiPref", type: "number", title: "Target AQI used for manual mode",
              defaultValue: 10, range: "0..500", required: true
        input name: "defaultOnLevel", type: "number", title: "Default level for on() when there is no previous level (%)",
              defaultValue: 50, range: "1..100", required: true
        input name: "minRpm", type: "number", title: "Minimum fan RPM (used to convert reported RPM to %)",
              defaultValue: 500, range: "100..1500", required: true
        input name: "maxRpm", type: "number", title: "Maximum fan RPM (used to convert reported RPM to %)",
              defaultValue: 2000, range: "1000..4000", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 minutes)", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

// =============================================================================
// Constants
// =============================================================================
@Field static final List<String> FAN_SPEEDS =
    ["off", "low", "medium-low", "medium", "medium-high", "high", "on", "auto"]

/** Named FanControl speeds mapped to the percentage Mila expects. */
@Field static final Map<String, Integer> SPEED_TO_PERCENT =
    ["low": 20, "medium-low": 40, "medium": 60, "medium-high": 80, "high": 100]

/**
 * Sensor kind -> [attribute, unit, decimal places].
 * Temperature and FanSpeed are handled separately.
 */
@Field static final Map<String, List> SENSOR_MAP = [
    "Aqi"        : ["airQualityIndex",   "",      0],
    "Pm1"        : ["pm1",               "µg/m³", 0],
    "Pm2_5"      : ["pm25",              "µg/m³", 0],
    "Pm10"       : ["pm10",              "µg/m³", 0],
    "Voc"        : ["voc",               "ppb",   0],
    "Co"         : ["carbonMonoxide",    "ppm",   1],
    "Co2"        : ["carbonDioxide",     "ppm",   0],
    "Humidity"   : ["humidity",          "%",     1],
    "Ach"        : ["airChangesPerHour", "cph",   1],
    "Ttc"        : ["timeToClean",       "min",   0]
]

// =============================================================================
// Lifecycle
// =============================================================================
void installed() {
    logDebug "installed()"
    sendEvent(name: "supportedFanSpeeds", value: JsonOutput.toJson(FAN_SPEEDS))
    sendEvent(name: "connectionStatus", value: "offline")
    runIn(3, "refresh")
}

void updated() {
    logDebug "updated()"
    unschedule()
    if (logEnable) runIn(1800, "disableDebugLogging")
    sendEvent(name: "supportedFanSpeeds", value: JsonOutput.toJson(FAN_SPEEDS))
    sendEvent(name: "targetAqi", value: targetAqiSetting())
    refresh()
}

void disableDebugLogging() {
    logInfo "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void refresh() {
    parent?.refreshAppliance(device)
}

// =============================================================================
// Commands - power and speed
// =============================================================================
void on() {
    Integer pct = lastNonZeroLevel()
    logDebug "on() -> ${pct}%"
    parent?.setManualMode(device, pct, targetAqiSetting())
    optimisticSpeed(pct)
}

void off() {
    logDebug "off()"
    // Mila turns the fan off via manual mode with a null speed.
    parent?.setManualMode(device, null, targetAqiSetting())
    optimisticSpeed(0)
}

void setLevel(level, duration = null) {
    if (duration != null) logDebug "setLevel duration is ignored; Mila has no fade"
    Integer pct = clampPercent(level)
    logDebug "setLevel(${pct})"
    if (pct == 0) { off(); return }
    parent?.setManualMode(device, pct, targetAqiSetting())
    optimisticSpeed(pct)
}

void setManualMode(percent) {
    setLevel(percent)
}

void setAutomagicMode() {
    logDebug "setAutomagicMode()"
    parent?.setAutomagicMode(device)
    sendEvent(name: "mode", value: "Automagic", descriptionText: "${device.displayName} mode is Automagic")
    sendEvent(name: "speed", value: "auto")
    sendEvent(name: "switch", value: "on")
}

void setSpeed(fanspeed) {
    logDebug "setSpeed(${fanspeed})"
    switch (fanspeed as String) {
        case "off":  off(); break
        case "on":   on();  break
        case "auto": setAutomagicMode(); break
        default:
            Integer pct = SPEED_TO_PERCENT[fanspeed as String]
            if (pct == null) {
                logWarn "Unsupported fan speed '${fanspeed}'"
                return
            }
            setLevel(pct)
    }
}

void cycleSpeed() {
    // off -> low -> medium-low -> medium -> medium-high -> high -> off
    String current = device.currentValue("speed") ?: "off"
    List<String> cycle = ["off", "low", "medium-low", "medium", "medium-high", "high"]
    int idx = cycle.indexOf(current)
    String next = cycle[(idx + 1) % cycle.size()]   // unknown/auto (idx -1) lands on "off"
    logDebug "cycleSpeed(): ${current} -> ${next}"
    setSpeed(next)
}

// =============================================================================
// Commands - smart modes and settings
// =============================================================================
// NOTE: the parameter is deliberately not called "state" - that name is the
// driver's persistent state map and shadowing it here would be a trap.
void setQuietMode(String onOff)       { applySmartMode("quiet", onOff) }
void setHousekeeperMode(String onOff) { applySmartMode("housekeeper", onOff) }
void setQuarantineMode(String onOff)  { applySmartMode("quarantine", onOff) }
void setPowerSaverMode(String onOff)  { applySmartMode("powerSaver", onOff) }
void setChildLock(String onOff)       { applySmartMode("childLock", onOff) }
void setSleepMode(String onOff, String fanMode = null)      { applySmartMode("sleep", onOff, fanMode ?: "Lowest") }
void setTurndownMode(String onOff, String fanMode = null)   { applySmartMode("turndown", onOff, fanMode ?: "Highest") }
void setWhitenoiseMode(String onOff, String fanMode = null) { applySmartMode("whitenoise", onOff, fanMode ?: "Medium") }

private void applySmartMode(String mode, String onOff, String fanMode = null) {
    boolean enabled = (onOff?.toLowerCase() == "on" || onOff?.toLowerCase() == "true")
    logDebug "${mode} -> ${enabled ? 'on' : 'off'}${fanMode ? " (${fanMode})" : ''}"
    parent?.setSmartMode(device, mode, enabled, fanMode)
}

void setSoundsConfig(String sounds) {
    logDebug "setSoundsConfig(${sounds})"
    parent?.setSoundsConfig(device, sounds)
}

void setBedtime(String start, String end) {
    if (!(start ==~ /^\d{1,2}:\d{2}$/) || !(end ==~ /^\d{1,2}:\d{2}$/)) {
        logWarn "Bedtime must be 24-hour HH:mm, e.g. 22:30. Got start='${start}' end='${end}'."
        return
    }
    logDebug "setBedtime(${start}, ${end})"
    parent?.setBedtime(device, padTime(start), padTime(end))
}

void startDeepClean(targetAch) {
    BigDecimal ach = toBigDecimal(targetAch)
    if (ach == null || ach <= 0) {
        logWarn "startDeepClean needs a positive target ACH"
        return
    }
    logDebug "startDeepClean(${ach})"
    parent?.startDeepClean(device, ach)
}

void calibrateFilter() {
    logDebug "calibrateFilter()"
    parent?.calibrateFilter(device)
}

void resetSensor(String sensor) {
    if (!(sensor in ["Co", "Voc"])) {
        logWarn "Only the Co and Voc sensors can be reset"
        return
    }
    logDebug "resetSensor(${sensor})"
    parent?.resetSensor(device, sensor)
}

// =============================================================================
// Data from the parent app
// =============================================================================
/**
 * Called by the parent app with one appliance from the Mila GraphQL response.
 * Every field is optional: Mila's reduced field set omits some of them, and an
 * offline appliance reports a null state.
 */
void parseApplianceData(Map appliance) {
    if (!appliance) return
    logDebug "parseApplianceData: ${appliance.id}"

    Map applianceState = appliance.state as Map
    // Mila signals "offline" with a null actualMode.
    boolean online = applianceState?.actualMode != null
    String modeName = online ? applianceState.actualMode.toString() : "offline"
    updateAttr("connectionStatus", online ? "online" : "offline")
    updateAttr("mode", modeName)

    if (applianceState?.modes != null) {
        updateAttr("activeModes", JsonOutput.toJson(applianceState.modes))
    }
    if (applianceState?.wifiRssi != null) {
        updateAttr("rssi", toNumber(applianceState.wifiRssi), "dBm")
    }
    if (applianceState?.firmware?.version) {
        updateAttr("firmware", applianceState.firmware.version.toString())
    }

    parseRoom(appliance.room as Map)
    parseFilter(appliance.filter as Map)
    parseSmartModes(appliance.smartModes as Map)
    // modeName is passed down rather than read back with currentValue(): an
    // attribute read immediately after its own sendEvent can still be stale.
    parseSensors(appliance.sensors as List, online, modeName)

    updateAttr("targetAqi", targetAqiSetting())
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

private void parseRoom(Map room) {
    if (!room) return
    if (room.name) updateAttr("roomName", room.name.toString())
    if (room.soundsConfig) updateAttr("soundsConfig", room.soundsConfig.toString())
    if (room.bedtime) {
        updateAttr("bedtimeStart", room.bedtime.localStart?.toString())
        updateAttr("bedtimeEnd", room.bedtime.localEnd?.toString())
    }
}

private void parseFilter(Map filter) {
    if (!filter) return
    if (filter.kind) updateAttr("filterKind", splitCamelCase(filter.kind.toString()))
    if (filter.installedAt != null) {
        updateAttr("filterInstalled", epochToDate(filter.installedAt))
    }
    if (filter.daysLeft != null) {
        Integer days = toNumber(filter.daysLeft) as Integer
        updateAttr("filterDaysLeft", days)
        // FilterStatus is a two-state capability; treat the last week as "replace".
        updateAttr("filterStatus", days <= 7 ? "replace" : "normal")
    }
}

private void parseSmartModes(Map modes) {
    if (!modes) return
    updateAttr("quietMode",       boolToOnOff(modes.quiet?.isEnabled))
    updateAttr("housekeeperMode", boolToOnOff(modes.housekeeper?.isEnabled))
    updateAttr("quarantineMode",  boolToOnOff(modes.quarantine?.isEnabled))
    updateAttr("sleepMode",       boolToOnOff(modes.sleep?.isEnabled))
    updateAttr("turndownMode",    boolToOnOff(modes.turndown?.isEnabled))
    updateAttr("whitenoiseMode",  boolToOnOff(modes.whitenoise?.isEnabled))
    updateAttr("powerSaverMode",  boolToOnOff(modes.powerSaver?.isEnabled))
    updateAttr("childLock",       boolToOnOff(modes.childLock?.isEnabled))
}

private void parseSensors(List sensors, boolean online, String modeName) {
    if (!sensors) return
    sensors.each { Map sensor ->
        String kind = sensor?.kind?.toString()
        def raw = sensor?.latest?.value
        if (kind == null || raw == null) return
        BigDecimal value = toBigDecimal(raw)
        if (value == null) return

        if (kind == "Temperature") {
            // Mila reports Celsius; present it in the hub's configured scale.
            BigDecimal temp = (location.temperatureScale == "F") ? celsiusToFahrenheit(value) : value
            updateAttr("temperature", round(temp, 1), "°${location.temperatureScale}")
            return
        }
        if (kind == "FanSpeed") {
            parseFanSpeed(value, online, modeName)
            return
        }
        List target = SENSOR_MAP[kind]
        if (!target) {
            logDebug "Ignoring unmapped sensor kind '${kind}'"
            return
        }
        updateAttr(target[0] as String, round(value, target[2] as Integer), target[1] as String)
    }
}

private void parseFanSpeed(BigDecimal rpm, boolean online, String modeName) {
    Integer rpmInt = rpm.setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    updateAttr("fanSpeedRpm", rpmInt, "rpm")

    if (!online) return   // do not claim the fan is off just because we lost contact

    Integer pct = rpmToPercent(rpmInt)
    updateAttr("level", pct, "%")
    updateAttr("switch", pct > 0 ? "on" : "off")

    // Automagic keeps choosing its own speed, so report "auto" rather than a
    // named speed that the user did not ask for.
    updateAttr("speed", (modeName == "Automagic" && pct > 0) ? "auto" : percentToSpeedName(pct))

    if (pct > 0) state.lastLevel = pct
}

// =============================================================================
// Conversions
// =============================================================================
private Integer rpmToPercent(Integer rpm) {
    if (rpm == null || rpm <= 0) return 0
    Integer lo = (settings.minRpm ?: 500) as Integer
    Integer hi = (settings.maxRpm ?: 2000) as Integer
    if (hi <= lo) return 0
    BigDecimal pct = ((rpm - lo) / (BigDecimal.valueOf(hi - lo))) * 100.0
    Integer clamped = Math.max(0, Math.min(100, pct.setScale(0, java.math.RoundingMode.HALF_UP).intValue()))
    // Mila's own app moves in steps of 10; snapping avoids values like 63%.
    return (Integer) (Math.round(clamped / 10.0d) * 10)
}

private String percentToSpeedName(Integer pct) {
    if (pct == null || pct <= 0) return "off"
    if (pct <= 20) return "low"
    if (pct <= 40) return "medium-low"
    if (pct <= 60) return "medium"
    if (pct <= 80) return "medium-high"
    return "high"
}

/**
 * Reflect the requested speed straight away. Mila needs several seconds to
 * report the new RPM, and the parent schedules a refresh that corrects this.
 */
private void optimisticSpeed(Integer pct) {
    updateAttr("level", pct, "%")
    updateAttr("switch", pct > 0 ? "on" : "off")
    updateAttr("speed", percentToSpeedName(pct))
    updateAttr("mode", "Manual")
    if (pct > 0) state.lastLevel = pct
}

private Integer lastNonZeroLevel() {
    Integer last = (state.lastLevel ?: 0) as Integer
    if (last > 0) return last
    Integer current = (device.currentValue("level") ?: 0) as Integer
    if (current > 0) return current
    return clampPercent(settings.defaultOnLevel ?: 50)
}

private Integer clampPercent(value) {
    BigDecimal bd = toBigDecimal(value)
    if (bd == null) return 0
    int pct = bd.setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    return Math.max(0, Math.min(100, pct))
}

private Integer targetAqiSetting() {
    Integer aqi = (settings.targetAqiPref ?: 10) as Integer
    return Math.max(0, Math.min(500, aqi))
}

private BigDecimal toBigDecimal(value) {
    if (value == null) return null
    if (value instanceof BigDecimal) return value
    try { return new BigDecimal(value.toString()) } catch (Exception ignored) { return null }
}

private Number toNumber(value) {
    BigDecimal bd = toBigDecimal(value)
    return bd == null ? null : (bd.scale() > 0 ? bd : bd.intValue())
}

private BigDecimal round(BigDecimal value, int places) {
    return value?.setScale(places, java.math.RoundingMode.HALF_UP)
}

private String boolToOnOff(value) {
    return value == null ? null : (value ? "on" : "off")
}

private String epochToDate(value) {
    Long secs = toBigDecimal(value)?.longValue()
    return secs == null ? null : new Date(secs * 1000L).format("yyyy-MM-dd", location.timeZone)
}

private String padTime(String t) {
    // Mila's LocalTime scalar expects HH:mm.
    def parts = t.split(":")
    return String.format("%02d:%02d", parts[0] as Integer, parts[1] as Integer)
}

private String splitCamelCase(String s) {
    return s?.replaceAll(/([a-z0-9])([A-Z])/, '$1 $2')
}

// =============================================================================
// Event helper
// =============================================================================
/** Sends an event only when the value actually changed, keeping the log clean. */
private void updateAttr(String name, value, String unit = null) {
    if (value == null) return
    def current = device.currentValue(name)
    if (current != null && current.toString() == value.toString()) return

    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    evt.descriptionText = "${device.displayName} ${name} is ${value}${unit ?: ''}"
    sendEvent(evt)
    logInfo evt.descriptionText
}

// =============================================================================
// Logging
// =============================================================================
private void logDebug(String msg) { if (settings.logEnable != false) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable != false) log.info  "${msg}" }
private void logWarn(String msg)  { log.warn  "${device.displayName}: ${msg}" }
private void logError(String msg) { log.error "${device.displayName}: ${msg}" }
