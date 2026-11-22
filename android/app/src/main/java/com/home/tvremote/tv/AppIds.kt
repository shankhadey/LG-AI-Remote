package com.home.tvremote.tv

/**
 * One name per app across TV brands. The rest of the app speaks LG's IDs ("netflix", "amazon"…, what it was built
 * on); [canonical] turns a Samsung ID into that name, [forBrand] turns the name back into the brand's ID.
 */
object AppIds {
    private val SAMSUNG = mapOf(
        "netflix" to "3201907018807", "youtube.leanback.v4" to "111299001912", "amazon" to "3201910019365",
        "com.disney.disneyplus-prod" to "3201901017640", "hulu" to "3201601007625", "com.apple.appletv" to "3201807016597",
        "com.wbd.stream" to "3202301029760", "spotify-beehive" to "3201606009684", "peacock" to "3202006020991",
        "paramount" to "3201710014981", "com.webos.app.browser" to "org.tizen.browser",
    )
    private val FROM_SAMSUNG = SAMSUNG.entries.associate { (k, v) -> v to k }

    fun canonical(id: String): String = FROM_SAMSUNG[id] ?: id
    fun forBrand(canonicalId: String, brand: String): String = if (brand == "Samsung") SAMSUNG[canonicalId] ?: canonicalId else canonicalId
}
