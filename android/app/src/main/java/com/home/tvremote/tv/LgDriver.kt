package com.home.tvremote.tv

import com.home.tvremote.lg.LgTv
import com.home.tvremote.voice.TvApp
import org.json.JSONObject

/** LG webOS over SSAP: a thin layer over [LgTv], with exactly the calls the app made before drivers existed. */
class LgDriver(val tv: LgTv) : TvDriver {
    override val brand = "LG"
    override val caps = TvDriver.Caps(pointer = true, playState = true, toasts = true)
    override val isConnected get() = tv.isConnected
    override val history get() = tv.history

    private suspend fun req(uri: String, payload: JSONObject? = null) = tv.request("ssap://$uri", payload)

    override suspend fun connect(onPrompt: () -> Unit) = tv.connect(onPrompt)
    override fun close() = tv.close()
    override fun dispose() = tv.dispose()

    override suspend fun powerState(): String? = req("com.webos.service.tvpower/power/getPowerState").optString("state").ifEmpty { null }
    override suspend fun turnOff() { req("system/turnOff") }
    override suspend fun turnOnScreen() { req("com.webos.service.tvpower/power/turnOnScreen") }

    override suspend fun modelName(): String? = req("system/getSystemInfo").optString("modelName").ifEmpty { null }
    override suspend fun installedApps(): List<TvApp>? {
        val lp = runCatching { req("com.webos.applicationManager/listLaunchPoints").optJSONArray("launchPoints") }.getOrNull() ?: return null
        return (0 until lp.length()).map { lp.getJSONObject(it) }.map { a ->
            TvApp(a.optString("id"), a.optString("title"), a.optString("largeIcon").ifEmpty { a.optString("icon") }.ifEmpty { null })
        }
    }
    override suspend fun foregroundApp(): String? = req("com.webos.applicationManager/getForegroundAppInfo").optString("appId").ifEmpty { null }
    override suspend fun macAddresses(): List<String> {
        val info = req("com.webos.service.connectionmanager/getinfo")
        return listOf("wifiInfo", "wiredInfo").mapNotNull { info.optJSONObject(it)?.optString("macAddress")?.takeIf { m -> m.length >= 12 } }
    }
    override suspend fun onPower(cb: (String) -> Unit) = tv.subscribe("ssap://com.webos.service.tvpower/power/getPowerState") { p ->
        p.optString("state").takeIf { it.isNotEmpty() }?.let(cb)
    }
    override suspend fun onForeground(cb: (String) -> Unit) = tv.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { p ->
        p.optString("appId").takeIf { it.isNotEmpty() }?.let(cb)
    }
    override suspend fun onMedia(cb: (List<Pair<String?, String?>>) -> Unit) = tv.subscribe("ssap://com.webos.media/getForegroundAppInfo") { p ->
        val list = p.optJSONArray("foregroundAppInfo")
        cb((0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.map { it.optString("appId").ifEmpty { null } to it.optString("playState").ifEmpty { null } })
    }
    override suspend fun onKeyboard(cb: (Boolean) -> Unit) = tv.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard") { p ->
        p.optJSONObject("currentWidget")?.let { cb(it.optBoolean("focus", false)) }
    }

    override suspend fun button(name: String) = tv.button(name)
    override suspend fun move(dx: Float, dy: Float) = tv.move(dx, dy)
    override suspend fun click() = tv.click()
    override suspend fun scroll(dy: Float) = tv.scroll(dy)
    override suspend fun volume(up: Boolean) { req(if (up) "audio/volumeUp" else "audio/volumeDown") }
    override suspend fun setVolume(level: Int) { req("audio/setVolume", JSONObject().put("volume", level)) }
    override suspend fun isMuted(): Boolean = req("audio/getStatus").optBoolean("mute")
    override suspend fun setMute(mute: Boolean) { req("audio/setMute", JSONObject().put("mute", mute)) }
    override suspend fun media(action: String) { req("media.controls/$action") }
    override suspend fun channel(up: Boolean) { req(if (up) "tv/channelUp" else "tv/channelDown") }
    override suspend fun openChannel(number: String) { req("tv/openChannel", JSONObject().put("channelNumber", number)) }
    override suspend fun openInput(hdmi: Int) { req("system.launcher/launch", JSONObject().put("id", "com.webos.app.hdmi$hdmi")) }
    override suspend fun launch(appId: String, extras: JSONObject?, alternative: Boolean) {
        if (alternative) { req("com.webos.applicationManager/launch", JSONObject().put("id", appId)); return }
        val payload = JSONObject().put("id", appId)
        extras?.let { x -> x.keys().forEach { k -> payload.put(k, x.get(k)) } }
        req("system.launcher/launch", payload)
    }
    override suspend fun openUrl(url: String): Boolean =
        runCatching { req("system.launcher/open", JSONObject().put("target", url)) }.isSuccess ||
            runCatching { req("system.launcher/launch", JSONObject().put("id", "com.webos.app.browser").put("params", JSONObject().put("target", url))) }.isSuccess
    override suspend fun toast(message: String) { req("system.notifications/createToast", JSONObject().put("message", message)) }
    override suspend fun typeText(text: String) { req("com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", true)) }
    override suspend fun sendEnter() { req("com.webos.service.ime/sendEnterKey") }
}
