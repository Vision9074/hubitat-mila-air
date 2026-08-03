/**
 *  Mila Air Integration (Unofficial)
 *  =================================
 *  Hubitat integration app for Mila air purifiers.
 *
 *  This app owns the Mila cloud connection for the whole hub:
 *    - holds the account credentials and the OAuth token,
 *    - discovers the appliances on the account and lets you pick which ones
 *      become Hubitat devices,
 *    - polls Mila once per cycle for ALL appliances and pushes the results down
 *      into the child devices,
 *    - executes commands on behalf of the child devices.
 *
 *  Requires the companion driver: "Mila Air Purifier".
 *
 *  ---------------------------------------------------------------------------
 *  API NOTES
 *  ---------------------------------------------------------------------------
 *  Mila is a *private* API. It can change or break without notice. Details here
 *  are derived from the two published reference implementations:
 *    https://github.com/sanghviharshit/ha-mila   (Home Assistant integration)
 *    https://pypi.org/project/milasdk/           (Python SDK + GraphQL schema)
 *
 *  Auth:  Keycloak at id.milacares.com, realm "prod", client "prod-ui", using
 *         the Resource Owner Password Credentials ("Direct Access Grant") flow.
 *         This is the same flow milasdk uses (oauthlib LegacyApplicationClient),
 *         so no browser redirect is needed.
 *
 *  Data:  GraphQL at https://graph-api.milacares.com/graphql. NOTE: the older
 *         REST endpoints under api.milacares.com/mms are legacy and are not
 *         used here.
 *
 *  Modes: Fan speed and auto/manual are properties of the ROOM, not of the
 *         appliance. If two Mila units share a room, changing the speed of one
 *         changes both. Smart modes (quiet, sleep, child lock, ...) are per
 *         appliance.
 *
 *  License: MIT
 */

import groovy.json.JsonOutput
import groovy.transform.Field

definition(
    name: "Mila Air Integration",
    namespace: "vision9074",
    author: "vision9074",
    description: "Connect Mila air purifiers to Hubitat via the Mila cloud API",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true,
    installOnOpen: true,
    documentationLink: "https://github.com/vision9074/hubitat-mila-air",
    importUrl: "https://raw.githubusercontent.com/vision9074/hubitat-mila-air/main/MilaAirIntegration.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "devicePage")
}

// =============================================================================
// Constants
// =============================================================================
@Field static final String AUTH_URL     = "https://id.milacares.com/auth/realms/prod/protocol/openid-connect/token"
@Field static final String GRAPHQL_URL  = "https://graph-api.milacares.com/graphql"
@Field static final String CLIENT_ID    = "prod-ui"
@Field static final String AUTH_SCOPE   = "email profile"

@Field static final String DRIVER_NAMESPACE = "vision9074"
@Field static final String DRIVER_NAME      = "Mila Air Purifier"

/** Seconds before nominal expiry that we proactively renew the access token. */
@Field static final int TOKEN_SKEW_SECONDS = 60
/** Mila returns HTTP 429 when polled too hard; back off for this long. */
@Field static final int RATE_LIMIT_BACKOFF_SECONDS = 60

/**
 * Full appliance selection set. `smartModes` and `filter.daysLeft` are in the
 * published schema but are not requested by ha-mila, so if Mila's live schema
 * disagrees we fall back to the reduced set below rather than losing the poll.
 */
@Field static final String FIELDS_FULL = '''
    id
    name
    room { id kind name size soundsConfig bedtime { localStart localEnd } }
    state { firmware { version hash } wifiRssi rawMode modes actualMode }
    filter { kind daysLeft installedAt calibratedAt }
    smartModes {
      quiet { isEnabled }
      housekeeper { isEnabled }
      quarantine { isEnabled }
      sleep { isEnabled fanMode }
      turndown { isEnabled fanMode }
      whitenoise { isEnabled fanMode }
      powerSaver { isEnabled }
      childLock { isEnabled }
    }
    sensors(kinds: [Ach, FanSpeed, Ttc, Aqi, Pm1, Pm2_5, Pm10, Voc, Humidity, Temperature, Co2, Co]) {
      kind
      latest(precision: { value: 1, unit: Minute }) { instant value }
    }
'''

/** Known-good reduced selection set (mirrors ha-mila's fragment). */
@Field static final String FIELDS_BASIC = '''
    id
    name
    room { id kind name size soundsConfig bedtime { localStart localEnd } }
    state { firmware { version hash } wifiRssi rawMode modes actualMode }
    filter { kind installedAt calibratedAt }
    sensors(kinds: [Ach, FanSpeed, Ttc, Aqi, Pm1, Pm2_5, Pm10, Voc, Humidity, Temperature, Co2, Co]) {
      kind
      latest(precision: { value: 1, unit: Minute }) { instant value }
    }
'''

/** Minimal selection used for discovery, so the device list loads fast. */
@Field static final String FIELDS_DISCOVERY = '''
    id
    name
    room { id kind name }
    state { firmware { version } actualMode }
'''

@Field static final Map POLL_OPTIONS = [
    "1" : "Every minute",
    "5" : "Every 5 minutes (recommended)",
    "10": "Every 10 minutes",
    "15": "Every 15 minutes",
    "30": "Every 30 minutes",
    "60": "Every hour"
]

// =============================================================================
// Pages
// =============================================================================
def mainPage() {
    dynamicPage(name: "mainPage", title: "<h2>Mila Air Integration</h2>", install: true, uninstall: true) {

        section("<b>Mila Account</b>") {
            if (isLoggedIn()) {
                paragraph "&#9989; Connected as <b>${settings.milaEmail}</b>"
            } else if (settings.milaEmail) {
                paragraph "&#9888; Not connected. ${state.authMessage ?: 'Open the account page and press Connect.'}"
            } else {
                paragraph "Enter your Mila account credentials to get started."
            }
            href name: "toAuthPage", page: "authPage",
                 title: "Mila account", description: settings.milaEmail ?: "Not configured"
        }

        if (isLoggedIn()) {
            section("<b>Devices</b>") {
                def kids = getChildDevices()
                if (kids) {
                    String list = kids.sort { it.displayName }
                                      .collect { "&bull; ${it.displayName}" }
                                      .join("<br>")
                    paragraph list
                } else {
                    paragraph "No Mila devices have been added yet."
                }
                href name: "toDevicePage", page: "devicePage",
                     title: "Add / remove Mila devices",
                     description: "${kids.size()} device${kids.size() == 1 ? '' : 's'} installed"
            }

            section("<b>Polling</b>") {
                input name: "pollInterval", type: "enum", title: "How often to refresh from Mila",
                      options: POLL_OPTIONS, defaultValue: "5", required: true, width: 6
                paragraph "One request covers every device, so a shorter interval does not " +
                          "multiply with the number of purifiers. Mila will return HTTP 429 " +
                          "if polled too aggressively."
                if (state.lastPollAt) {
                    paragraph "Last successful refresh: ${formatTs(state.lastPollAt)}"
                }
                if (state.lastError) {
                    paragraph "&#9888; Last error: ${state.lastError}"
                }
            }

            section("<b>Options</b>") {
                input name: "autoRename", type: "bool", width: 6,
                      title: "Rename devices when they are renamed in the Mila app",
                      defaultValue: false
                input name: "logEnable", type: "bool", width: 6,
                      title: "Enable debug logging (auto-off after 30 minutes)", defaultValue: true
                input name: "txtEnable", type: "bool", width: 6,
                      title: "Enable descriptive text logging", defaultValue: true
            }
        }

        section {
            label title: "Name this instance", required: false
        }
    }
}

def authPage() {
    dynamicPage(name: "authPage", title: "<h2>Mila Account</h2>") {
        section {
            paragraph "These are the same credentials you use in the Mila app. They are " +
                      "sent only to Mila's own login server (id.milacares.com)."
            input name: "milaEmail", type: "text", title: "Email address",
                  required: true, submitOnChange: true
            input name: "milaPassword", type: "password", title: "Password",
                  required: true, submitOnChange: true
        }
        section {
            input name: "btnConnect", type: "button", title: "Connect to Mila", width: 4
            if (state.authMessage) {
                paragraph state.authMessage
            }
        }
        section {
            paragraph "<small>If login fails with <i>invalid_grant</i>, the email or password " +
                      "is wrong. If it fails with <i>unauthorized_client</i>, Mila has disabled " +
                      "direct password login for the app client and this integration cannot " +
                      "authenticate.</small>"
        }
    }
}

def devicePage() {
    if (!ensureToken()) {
        return dynamicPage(name: "devicePage", title: "<h2>Mila Devices</h2>") {
            section {
                paragraph "&#9888; Not connected to Mila. ${state.authMessage ?: ''}"
                href name: "backToAuth", page: "authPage", title: "Fix account settings", description: ""
            }
        }
    }

    // Cache discovery so submitOnChange re-renders do not hammer the API.
    Long age = state.discoveredAt ? (now() - (state.discoveredAt as Long)) : Long.MAX_VALUE
    if (age > 60000L) discoverAppliances()

    Map<String, Map> found = (state.discoveredAppliances ?: [:]) as Map
    Map<String, String> options = found.collectEntries { id, info -> [(id): info.label] }

    dynamicPage(name: "devicePage", title: "<h2>Mila Devices</h2>") {
        section {
            if (options) {
                paragraph "Found ${options.size()} Mila appliance${options.size() == 1 ? '' : 's'} on this account."
                input name: "selectedAppliances", type: "enum", title: "Devices to add to Hubitat",
                      options: options, multiple: true, required: false, submitOnChange: true
            } else {
                paragraph "&#9888; No appliances were returned for this account. " +
                          "${state.lastError ?: 'Confirm the units are set up in the Mila app.'}"
            }
        }
        section {
            input name: "btnSync", type: "button", title: "Create / Remove Devices", width: 4
            input name: "btnRescan", type: "button", title: "Re-scan Mila Account", width: 4
            if (state.syncMessage) paragraph state.syncMessage
        }
        section {
            paragraph "<small>Unchecking a device and pressing <b>Create / Remove Devices</b> " +
                      "deletes it from Hubitat along with its history. Devices that Mila did " +
                      "not return in the last scan are never auto-deleted.</small>"
        }
    }
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnConnect":
            state.remove("authMessage")
            if (login()) {
                state.authMessage = "&#9989; Connected successfully."
                discoverAppliances()
            }
            break
        case "btnSync":
            syncChildDevices()
            break
        case "btnRescan":
            discoverAppliances()
            int n = (state.discoveredAppliances ?: [:]).size()
            state.syncMessage = "Re-scanned: found ${n} appliance(s).".toString()
            break
    }
}

// =============================================================================
// Lifecycle
// =============================================================================
void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unschedule()
    initialize()
}

void uninstalled() {
    logInfo "Removing all Mila child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private void initialize() {
    state.remove("syncMessage")
    // Re-try the extended field set after every save, in case a previous
    // fallback was caused by a transient Mila-side problem.
    state.remove("useBasicFields")
    if (logEnable) runIn(1800, "disableDebugLogging")
    if (!settings.milaEmail || !settings.milaPassword) {
        logWarn "Mila credentials are not set; polling is not scheduled."
        return
    }
    syncChildDevices()
    schedulePolling()
    runIn(5, "refreshAll")
}

void disableDebugLogging() {
    logInfo "Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void schedulePolling() {
    String interval = settings.pollInterval ?: "5"
    switch (interval) {
        case "1":  runEvery1Minute("refreshAll");   break
        case "5":  runEvery5Minutes("refreshAll");  break
        case "10": runEvery10Minutes("refreshAll"); break
        case "15": runEvery15Minutes("refreshAll"); break
        case "30": runEvery30Minutes("refreshAll"); break
        case "60": runEvery1Hour("refreshAll");     break
        default:   runEvery5Minutes("refreshAll")
    }
    logDebug "Polling scheduled: ${POLL_OPTIONS[interval]}"
}

// =============================================================================
// Authentication
// =============================================================================
private boolean isLoggedIn() {
    return state.accessToken != null && settings.milaEmail
}

/**
 * Guarantees a usable access token, renewing it if it is at or near expiry.
 * Synchronous by design: this is one short request and every caller needs the
 * answer before it can build its own request.
 */
private boolean ensureToken() {
    if (!settings.milaEmail || !settings.milaPassword) {
        state.authMessage = "Mila email and password are required."
        return false
    }
    Long expiry = (state.tokenExpiresAt ?: 0L) as Long
    if (state.accessToken && now() < (expiry - (TOKEN_SKEW_SECONDS * 1000L))) {
        return true
    }
    if (state.refreshToken && refreshAccessToken()) {
        return true
    }
    return login()
}

private boolean login() {
    logDebug "Requesting a new access token"
    Map body = [
        grant_type: "password",
        client_id : CLIENT_ID,
        username  : settings.milaEmail,
        password  : settings.milaPassword,
        scope     : AUTH_SCOPE
    ]
    return postToken(body, "login")
}

private boolean refreshAccessToken() {
    logDebug "Refreshing the access token"
    Map body = [
        grant_type   : "refresh_token",
        client_id    : CLIENT_ID,
        refresh_token: state.refreshToken
    ]
    if (postToken(body, "refresh")) return true
    // A dead refresh token is normal after a long idle period; drop it quietly
    // so the caller falls through to a full login.
    state.remove("refreshToken")
    return false
}

private boolean postToken(Map body, String what) {
    Map params = [
        uri               : AUTH_URL,
        body              : body,
        requestContentType: "application/x-www-form-urlencoded",
        contentType       : "application/json",
        timeout           : 30
    ]
    boolean ok = false
    try {
        httpPost(params) { resp ->
            Map json = resp.data as Map
            if (json?.access_token) {
                state.accessToken   = json.access_token
                state.refreshToken  = json.refresh_token
                state.tokenExpiresAt = now() + (((json.expires_in ?: 300) as Long) * 1000L)
                state.remove("lastError")
                logDebug "Token ${what} succeeded (expires in ${json.expires_in}s)"
                ok = true
            } else {
                state.authMessage = "&#10060; Mila did not return an access token."
                logError state.authMessage
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        String detail = describeAuthError(e)
        noteAuthFailure(what, detail, what == "login")
    } catch (Exception e) {
        noteAuthFailure(what, e.message, true)
    }
    return ok
}

/**
 * A failed refresh is recoverable - the caller falls back to a full login - so
 * only the login path is logged as an error.
 */
private void noteAuthFailure(String what, String detail, boolean hard) {
    String msg = "&#10060; Token ${what} failed: ${detail}"
    state.authMessage = msg
    state.lastError = "Token ${what} failed: ${detail}".toString()
    if (hard) logError msg else logDebug msg
}

private String describeAuthError(groovyx.net.http.HttpResponseException e) {
    String code = null
    String description = null
    try {
        def data = e.response?.data
        if (data instanceof Map) {
            code = data.error
            description = data.error_description
        }
    } catch (ignored) { /* error body was not JSON */ }
    if (code == "invalid_grant")        return "invalid_grant - the email or password is not accepted by Mila"
    if (code == "unauthorized_client")  return "unauthorized_client - Mila has disabled direct password login for this client"
    return "HTTP ${e.statusCode}${code ? " ${code}" : ''}${description ? " (${description})" : ''}"
}

// =============================================================================
// Discovery
// =============================================================================
/** Synchronous: the device page needs the result in order to render. */
private void discoverAppliances() {
    if (!ensureToken()) return

    String query = 'query Discover { owner { appliances { ' + FIELDS_DISCOVERY + ' } } }'
    Map result = gqlSync(query, null)
    if (!result.ok && result.reauth && login()) {
        logDebug "Discovery hit a stale token; retrying once with a fresh one"
        result = gqlSync(query, null)
    }
    if (!result.ok) {
        noteError("Discovery failed: ${result.error}")
        return
    }

    Map<String, Map> found = [:]
    (result.data?.owner?.appliances ?: []).each { Map a ->
        String id = a.id?.toString()
        if (!id) return
        found[id] = [
            id      : id,
            label   : applianceLabel(a),
            roomId  : a.room?.id?.toString(),
            roomName: a.room?.name,
            roomKind: a.room?.kind,
            firmware: a.state?.firmware?.version
        ]
    }
    state.discoveredAppliances = found
    state.discoveredAt = now()
    state.remove("lastError")
    logDebug "Discovered ${found.size()} appliance(s): ${found.values()*.label}"
}

/** Mila's own precedence: appliance name, else room name, else room kind. */
private String applianceLabel(Map appliance) {
    String name = appliance.name?.toString()?.trim()
    if (name) return name
    String room = appliance.room?.name?.toString()?.trim()
    if (room) return room
    String kind = appliance.room?.kind?.toString()
    return kind ? "Mila ${splitCamelCase(kind)}" : "Mila ${appliance.id}"
}

private String splitCamelCase(String s) {
    return s?.replaceAll(/([a-z0-9])([A-Z])/, '$1 $2')
}

// =============================================================================
// Child device management
// =============================================================================
private String childDni(String applianceId) {
    return "mila-" + applianceId.toLowerCase().replaceAll(/[^a-z0-9]/, "")
}

void syncChildDevices() {
    Map<String, Map> discovered = (state.discoveredAppliances ?: [:]) as Map
    List<String> selected = (settings.selectedAppliances ?: []) as List
    List<String> created = []
    List<String> removed = []

    selected.each { String id ->
        Map info = discovered[id]
        if (!info) {
            logWarn "Selected appliance ${id} is not in the last scan; skipping."
            return
        }
        if (createChildDevice(info)) created << info.label
    }

    // Only remove devices Mila still reports but the user has deselected. A
    // device missing from discovery may just be a transient API failure, so it
    // is left alone.
    getChildDevices().each { cd ->
        String aid = cd.getDataValue("applianceId")
        if (aid && discovered.containsKey(aid) && !selected.contains(aid)) {
            removed << cd.displayName
            deleteChildDevice(cd.deviceNetworkId)
        }
    }

    List<String> parts = []
    if (created) parts << "Added: ${created.join(', ')}"
    if (removed) parts << "Removed: ${removed.join(', ')}"
    state.syncMessage = parts ? parts.join("<br>") : "No changes; devices are in sync."
    if (parts) logInfo state.syncMessage.replaceAll("<br>", " | ")

    if (created) runIn(3, "refreshAll")
}

private boolean createChildDevice(Map info) {
    String dni = childDni(info.id as String)
    def child = getChildDevice(dni)
    if (child) {
        // Keep the room mapping current: rooms can be reassigned in the app.
        child.updateDataValue("roomId", info.roomId?.toString())
        return false
    }
    try {
        child = addChildDevice(DRIVER_NAMESPACE, DRIVER_NAME, dni,
                               [name: DRIVER_NAME, label: info.label, isComponent: false])
        child.updateDataValue("applianceId", info.id as String)
        child.updateDataValue("roomId", info.roomId?.toString())
        logInfo "Created device '${info.label}'"
        return true
    } catch (Exception e) {
        logError "Could not create device '${info.label}': ${e.message}. " +
                 "Confirm the '${DRIVER_NAME}' driver is installed in namespace '${DRIVER_NAMESPACE}'."
        return false
    }
}

// =============================================================================
// Polling
// =============================================================================
void refreshAll() {
    if (isRateLimited()) {
        logDebug "Skipping refresh: backing off after a rate limit response"
        return
    }
    if (!getChildDevices()) {
        logDebug "No child devices; nothing to refresh"
        return
    }
    if (!ensureToken()) return

    String query = 'query GetAppliances { owner { appliances { ' + applianceFields() + ' } } }'
    gqlAsync(query, null, "refreshAllCallback", [op: "refreshAll"])
}

void refreshAllCallback(resp, Map data) {
    Map result = readGqlResponse(resp, data)
    if (!result.ok) return

    List appliances = result.data?.owner?.appliances ?: []
    logDebug "Refresh returned ${appliances.size()} appliance(s)"

    Map byApplianceId = [:]
    getChildDevices().each { cd ->
        String aid = cd.getDataValue("applianceId")
        if (aid) byApplianceId[aid] = cd
    }

    appliances.each { Map a ->
        def cd = byApplianceId[a.id?.toString()]
        if (cd) distributeToChild(cd, a)
    }
    state.lastPollAt = now()
    state.remove("lastError")
}

/** Refresh a single appliance; used by the driver's refresh() command. */
void refreshAppliance(cd) {
    String applianceId = cd.getDataValue("applianceId")
    if (!applianceId) {
        logWarn "${cd.displayName} has no applianceId; re-add it from the app."
        return
    }
    if (!ensureToken()) return

    String query = 'query GetAppliance($applianceId: MacAddress!) { owner { appliance(applianceId: $applianceId) { ' +
                   applianceFields() + ' } } }'
    gqlAsync(query, [applianceId: applianceId], "refreshApplianceCallback",
             [op: "refreshAppliance", dni: cd.deviceNetworkId])
}

void refreshApplianceCallback(resp, Map data) {
    Map result = readGqlResponse(resp, data)
    if (!result.ok) return

    Map appliance = result.data?.owner?.appliance as Map
    def cd = getChildDevice(data.dni as String)
    if (appliance && cd) {
        distributeToChild(cd, appliance)
        state.lastPollAt = now()
        state.remove("lastError")
    }
}

private void distributeToChild(cd, Map appliance) {
    // Rooms can be reassigned in the Mila app; keep the mapping fresh so
    // fan-speed commands target the right room.
    String roomId = appliance.room?.id?.toString()
    if (roomId && roomId != cd.getDataValue("roomId")) {
        cd.updateDataValue("roomId", roomId)
    }
    if (settings.autoRename) {
        String label = applianceLabel(appliance)
        if (label && label != cd.getLabel()) {
            logInfo "Renaming '${cd.getLabel()}' to '${label}' to match Mila"
            cd.setLabel(label)
        }
    }
    try {
        cd.parseApplianceData(appliance)
    } catch (Exception e) {
        logError "${cd.displayName} could not process its update: ${e.message}"
    }
}

private String applianceFields() {
    return state.useBasicFields ? FIELDS_BASIC : FIELDS_FULL
}

// =============================================================================
// Commands invoked by child devices
// =============================================================================
/** fanSpeed is a percentage 0..100; null turns the fan off. */
void setManualMode(cd, Integer fanSpeedPercent, Integer targetAqi) {
    String roomId = requireRoomId(cd)
    if (!roomId) return
    String mutation = '''
mutation SetManual($roomId: ID!, $fanSpeed: Int, $targetAqi: Int!) {
  applyRoomManualMode(roomId: $roomId, fanSpeed: $fanSpeed, targetAqi: $targetAqi) { id }
  forceRoomData(roomId: $roomId) { id }
}'''
    Map vars = [roomId: roomId, fanSpeed: fanSpeedPercent, targetAqi: (targetAqi ?: 10)]
    logDebug "${cd.displayName}: manual mode, speed=${fanSpeedPercent ?: 'off'}%, targetAqi=${vars.targetAqi}"
    runMutation(cd, mutation, vars, "setManualMode")
}

void setAutomagicMode(cd) {
    String roomId = requireRoomId(cd)
    if (!roomId) return
    String mutation = '''
mutation SetAutomagic($roomId: ID!) {
  applyRoomAutomagicMode(roomId: $roomId) { id }
  forceRoomData(roomId: $roomId) { id }
}'''
    logDebug "${cd.displayName}: automagic mode"
    runMutation(cd, mutation, [roomId: roomId], "setAutomagicMode")
}

/**
 * mode is one of quiet, housekeeper, quarantine, powerSaver, childLock (no fan
 * mode) or sleep, turndown, whitenoise (which take a FanMode).
 */
void setSmartMode(cd, String mode, boolean enabled, String fanMode = null) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return

    String mutation
    Map vars = [applianceId: applianceId, isEnabled: enabled]
    switch (mode) {
        case "quiet":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!) { applyQuietMode(applianceId: $applianceId, isEnabled: $isEnabled) { id } }'
            break
        case "housekeeper":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!) { applyHousekeeperMode(applianceId: $applianceId, isEnabled: $isEnabled) { id } }'
            break
        case "quarantine":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!) { applyQuarantineMode(applianceId: $applianceId, isEnabled: $isEnabled) { id } }'
            break
        case "powerSaver":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!) { applyPowerSaverMode(applianceId: $applianceId, isEnabled: $isEnabled) { id } }'
            break
        case "childLock":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!) { applyChildLockMode(applianceId: $applianceId, isEnabled: $isEnabled) { id } }'
            break
        case "sleep":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!, $fanMode: FanMode!) { applySleepMode(applianceId: $applianceId, isEnabled: $isEnabled, fanMode: $fanMode) { id } }'
            vars.fanMode = fanMode ?: "Lowest"
            break
        case "turndown":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!, $fanMode: FanMode!) { applyTurndownMode(applianceId: $applianceId, isEnabled: $isEnabled, fanMode: $fanMode) { id } }'
            vars.fanMode = fanMode ?: "Highest"
            break
        case "whitenoise":
            mutation = 'mutation M($applianceId: MacAddress!, $isEnabled: Boolean!, $fanMode: FanMode!) { applyWhitenoiseMode(applianceId: $applianceId, isEnabled: $isEnabled, fanMode: $fanMode) { id } }'
            vars.fanMode = fanMode ?: "Medium"
            break
        default:
            logWarn "Unknown smart mode '${mode}'"
            return
    }
    logDebug "${cd.displayName}: ${mode} = ${enabled}${vars.fanMode ? " (${vars.fanMode})" : ''}"
    runMutation(cd, mutation, vars, "setSmartMode:${mode}")
}

void setSoundsConfig(cd, String soundsConfig) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return
    String mutation = 'mutation M($applianceId: MacAddress!, $soundsConfig: SoundsConfig!) { applySoundsConfig(applianceId: $applianceId, soundsConfig: $soundsConfig) { id } }'
    runMutation(cd, mutation, [applianceId: applianceId, soundsConfig: soundsConfig], "setSoundsConfig")
}

void setBedtime(cd, String localStart, String localEnd) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return
    String mutation = 'mutation M($applianceId: MacAddress!, $localStart: LocalTime!, $localEnd: LocalTime!) { applyLocalBedtime(applianceId: $applianceId, localStart: $localStart, localEnd: $localEnd) { id } }'
    runMutation(cd, mutation, [applianceId: applianceId, localStart: localStart, localEnd: localEnd], "setBedtime")
}

void startDeepClean(cd, BigDecimal targetAch) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return
    String mutation = 'mutation M($applianceId: MacAddress!, $targetAchToReach: Float!) { startDeepClean(applianceId: $applianceId, targetAchToReach: $targetAchToReach) { timeToReachTargetAch } }'
    runMutation(cd, mutation, [applianceId: applianceId, targetAchToReach: targetAch], "startDeepClean")
}

void calibrateFilter(cd) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return
    String mutation = 'mutation M($applianceId: MacAddress!) { calibrateFilter(applianceId: $applianceId) { id } }'
    runMutation(cd, mutation, [applianceId: applianceId], "calibrateFilter")
}

/** sensor is "Co" or "Voc". */
void resetSensor(cd, String sensor) {
    String applianceId = requireApplianceId(cd)
    if (!applianceId) return
    String mutation = 'mutation M($applianceId: MacAddress!, $sensor: ResettableApplianceSensor!) { resetApplianceSensor(applianceId: $applianceId, sensor: $sensor) { id } }'
    runMutation(cd, mutation, [applianceId: applianceId, sensor: sensor], "resetSensor")
}

private void runMutation(cd, String mutation, Map vars, String label) {
    if (!ensureToken()) return
    gqlAsync(mutation, vars, "mutationCallback",
             [op: label, dni: cd.deviceNetworkId])
}

void mutationCallback(resp, Map data) {
    Map result = readGqlResponse(resp, data)
    if (!result.ok) return
    logDebug "${data.op} succeeded"
    // The appliance takes a few seconds to report its new state.
    runIn(6, "refreshAll", [overwrite: true])
}

private String requireApplianceId(cd) {
    String id = cd?.getDataValue("applianceId")
    if (!id) logWarn "${cd?.displayName} has no applianceId; re-add it from the app."
    return id
}

private String requireRoomId(cd) {
    String id = cd?.getDataValue("roomId")
    if (!id) logWarn "${cd?.displayName} has no roomId; refresh it or re-add it from the app."
    return id
}

// =============================================================================
// GraphQL transport
// =============================================================================
private Map gqlParams(String query, Map variables) {
    return [
        uri               : GRAPHQL_URL,
        headers           : [Authorization: "Bearer ${state.accessToken}".toString()],
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson([query: query, variables: variables ?: [:]]),
        timeout           : 30
    ]
}

/**
 * Synchronous GraphQL call.
 * Returns [ok: boolean, data: Map, error: String, reauth: boolean].
 */
private Map gqlSync(String query, Map variables) {
    Map outcome = [ok: false, data: null, error: null, reauth: false]
    try {
        httpPost(gqlParams(query, variables)) { resp ->
            Map body = resp.data as Map
            String err = graphqlErrors(body)
            if (err) {
                outcome.error = err
            } else {
                outcome.ok = true
                outcome.data = body?.data
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 401) {
            state.remove("accessToken")
            state.remove("tokenExpiresAt")
            outcome.error = "Mila rejected the access token (401)."
            outcome.reauth = true
        } else if (e.statusCode == 429) {
            noteRateLimit()
            outcome.error = "Mila rate limited the request (429)."
        } else {
            outcome.error = "HTTP ${e.statusCode}: ${e.message}"
        }
    } catch (Exception e) {
        outcome.error = e.message
    }
    return outcome
}

/** Asynchronous GraphQL call. `data` is echoed back to the callback. */
private void gqlAsync(String query, Map variables, String callback, Map data) {
    Map ctx = (data ?: [:]) + [query: query, variables: variables, callback: callback]
    try {
        asynchttpPost(callback, gqlParams(query, variables), ctx)
    } catch (Exception e) {
        logError "${ctx.op}: could not send request: ${e.message}"
    }
}

/**
 * Shared response handling for every async GraphQL callback. Handles 401 (one
 * re-auth and retry), 429 (back off) and GraphQL-level errors, including the
 * fall back to the reduced field set if Mila rejects a field we asked for.
 *
 * Returns [ok: boolean, data: Map].
 */
private Map readGqlResponse(resp, Map data) {
    String op = data?.op ?: "request"

    if (resp.hasError()) {
        Integer status = resp.status
        if (status == 401 && !data.retried) {
            logDebug "${op}: 401 from Mila, re-authenticating and retrying once"
            state.remove("accessToken")
            state.remove("tokenExpiresAt")
            if (ensureToken()) {
                asynchttpPost(data.callback as String,
                              gqlParams(data.query as String, data.variables as Map),
                              data + [retried: true])
            }
            return [ok: false]
        }
        if (status == 429) {
            noteRateLimit()
            noteError("Mila rate limited the hub (429); backing off ${RATE_LIMIT_BACKOFF_SECONDS}s.", true)
            return [ok: false]
        }
        noteError("${op} failed: HTTP ${status} ${resp.getErrorMessage() ?: ''}")
        return [ok: false]
    }

    Map body
    try {
        body = resp.getJson() as Map
    } catch (Exception e) {
        noteError("${op}: Mila did not return JSON (${e.message})")
        return [ok: false]
    }

    String err = graphqlErrors(body)
    if (err) {
        // A rejected field means our extended selection set is ahead of Mila's
        // live schema. Drop to the known-good set rather than losing polling.
        if (!state.useBasicFields && looksLikeSchemaError(err)) {
            state.useBasicFields = true
            logWarn "Mila rejected part of the extended query, falling back to the basic field set. Detail: ${err}"
            runIn(5, "refreshAll", [overwrite: true])
            return [ok: false]
        }
        noteError("${op}: ${err}")
        return [ok: false]
    }

    return [ok: true, data: body?.data]
}

/**
 * Records an error for display on the main page. The String parameter matters:
 * it coerces GStrings at the call boundary, and only plain Strings should be
 * written into state.
 */
private void noteError(String msg, boolean warnOnly = false) {
    state.lastError = msg
    if (warnOnly) logWarn msg else logError msg
}

private String graphqlErrors(Map body) {
    if (!body) return "empty response"
    List errors = body.errors as List
    if (!errors) return null
    return errors.collect { it?.message ?: it?.toString() }.join("; ")
}

private boolean looksLikeSchemaError(String err) {
    String e = err.toLowerCase()
    return e.contains("cannot query field") || e.contains("unknown field") ||
           e.contains("undefined field")    || e.contains("validation")
}

private void noteRateLimit() {
    state.rateLimitedUntil = now() + (RATE_LIMIT_BACKOFF_SECONDS * 1000L)
}

private boolean isRateLimited() {
    Long until = (state.rateLimitedUntil ?: 0L) as Long
    return now() < until
}

// =============================================================================
// Helpers
// =============================================================================
private String formatTs(Long ts) {
    return ts ? new Date(ts).format("yyyy-MM-dd HH:mm:ss", location.timeZone) : "never"
}

private void logDebug(String msg) { if (settings.logEnable != false) log.debug "Mila: ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable != false) log.info  "Mila: ${msg}" }
private void logWarn(String msg)  { log.warn  "Mila: ${msg}" }
private void logError(String msg) { log.error "Mila: ${msg}" }
