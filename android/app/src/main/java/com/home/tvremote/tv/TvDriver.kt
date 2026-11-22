package com.home.tvremote.tv

import com.home.tvremote.voice.TvApp
import org.json.JSONObject

/**
 * Everything the app asks of a TV, independent of the brand. LG (webOS) and Samsung (Tizen) each implement it;
 * the rest of the app never sees a protocol. Capabilities differ, so some calls are optional (see [Caps]).
 *
 * Power states use LG's words, which the app already understands: "Active", "Active Standby", "Screen Off",
 * "Suspend", "Power Off"; null = the TV won't say.
 */
interface TvDriver {
    val brand: String
    val caps: Caps
    val isConnected: Boolean
    /** Requests and answers, newest first, for Diagnostics. */
    val history: java.util.Deque<String>

    suspend fun connect(onPrompt: () -> Unit = {})
    fun close()
    /** Closes for good: the app is done with this TV object; nothing may reopen it (a late request used to). */
    fun dispose() = close()

    // power
    suspend fun powerState(): String?
    suspend fun turnOff()
    suspend fun turnOnScreen() {}

    // what the TV can tell us
    suspend fun modelName(): String?
    /** Installed apps, or null when the TV won't list them to this remote. */
    suspend fun installedApps(): List<TvApp>?
    suspend fun foregroundApp(): String?
    suspend fun macAddresses(): List<String>
    suspend fun onPower(cb: (String) -> Unit) {}
    suspend fun onForeground(cb: (String) -> Unit) {}
    /** (appId, "playing"/"paused"/…) for whatever is playing; called with an empty list when nothing is. */
    suspend fun onMedia(cb: (List<Pair<String?, String?>>) -> Unit) {}
    /** true when a text box on the TV is selected (its keyboard is up). */
    suspend fun onKeyboard(cb: (Boolean) -> Unit) {}

    // control
    /** Remote buttons by LG name: ENTER, BACK, HOME, UP, DOWN, LEFT, RIGHT, MENU, INFO… mapped per brand. */
    suspend fun button(name: String)
    suspend fun move(dx: Float, dy: Float)
    suspend fun click()
    suspend fun scroll(dy: Float)
    suspend fun volume(up: Boolean)
    suspend fun setVolume(level: Int)
    suspend fun isMuted(): Boolean
    suspend fun setMute(mute: Boolean)
    /** play, pause, stop, rewind, fastForward */
    suspend fun media(action: String)
    suspend fun channel(up: Boolean)
    suspend fun openChannel(number: String)
    suspend fun openInput(hdmi: Int)
    /**
     * Open an app, optionally at a title / with parameters. [extras] use LG's shape ({contentId} or {params:{contentTarget}});
     * other brands translate. [alternative] = the brand's second way of launching, for apps that ignore the first.
     */
    suspend fun launch(appId: String, extras: JSONObject? = null, alternative: Boolean = false)
    suspend fun openUrl(url: String): Boolean
    suspend fun toast(message: String) {}
    suspend fun typeText(text: String)
    suspend fun sendEnter()

    data class Caps(
        /** A free-moving pointer (LG Magic Remote). Without it the touchpad becomes swipe-for-arrows. */
        val pointer: Boolean,
        /** The TV reports play state for the app in front. */
        val playState: Boolean,
        /** On-screen notifications. */
        val toasts: Boolean,
    )
}
