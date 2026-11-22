package com.home.tvremote

import com.home.tvremote.i18n.I18n
import com.home.tvremote.i18n.Regions
import com.home.tvremote.voice.Command
import com.home.tvremote.voice.CommandParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Portuguese: every string the screens show has a translation; Portuguese voice commands work; countries. */
class I18nTest {
    @After fun reset() { I18n.lang = "en"; I18n.country = "US" }

    /** User-visible string literals in the screens and messages, with names replaced by "X". */
    private fun visibleStrings(): Set<String> {
        val src = File("src/main/java/com/home/tvremote")
        val files = listOf("ui/Screens.kt", "ui/PicksScreens.kt", "ui/Touchpad.kt", "TvController.kt", "picks/PicksController.kt", "timer/TimerService.kt", "ui/Onboarding.kt")
        val call = Regex("""\b(?:Text|SettingRow|Section|Label|Chip|say|note|toastOnTv|tr|PrimaryButton|SecondaryButton|Pill|ChoiceDialog|PicksException|I18n\.t)\(\s*(?:if \([^)]*\) )?"((?:[^"\\]|\\.)*)"""")
        val out = mutableSetOf<String>()
        val pair = Regex("""\b(?:Point|Check)\([^"]*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"""")
        for (f in files) for (m in pair.findAll(File(src, f).readText())) { out += m.groupValues[1].replace("\\\"", "\""); out += m.groupValues[2].replace("\\\"", "\"") }
        val path = Regex("""val path = if \([^)]*\) "([^"]*)"\s*else "([^"]*)"""")
        for (f in files) for (m in path.findAll(File(src, f).readText())) { out += m.groupValues[1]; out += m.groupValues[2] }
        for (f in files) for (m in call.findAll(File(src, f).readText())) {
            val s = m.groupValues[1].replace(Regex("""\$\{[^}]*\}"""), "X").replace(Regex("""\$[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*"""), "X").replace("\\\"", "\"")
            if ("\${" in m.groupValues[1].substringAfterLast("}")) continue   // a quote inside a template: not a whole string
            if (Regex("[A-Za-z]{2}").containsMatchIn(s.replace("X", ""))) out += s
        }
        return out
    }

    /** Words that are the same in Portuguese (brands, units, symbols). */
    private val SAME = setOf("Netflix", "TV", "LG", "Samsung", "YouTube", "Prime Video", "Disney+", "Max", "Apple TV+", "Hulu", "HDMI", "ENTER",
        "RIGHT", "LEFT", "Timer", "VOL", "VOLUME", "+10 min", "Drama", "Crime", "Romance", "“Volume 20”", "X s", "X min", "Volume X", "HDMI X", "X", "X · X", "min")

    @Test fun everyVisibleStringHasPortuguese() {
        I18n.lang = "pt"
        assertTrue("scanner finds the screens' strings", visibleStrings().size > 150)
        val missing = visibleStrings().filter { it !in SAME && I18n.translate(it) == null }.sorted()
        assertTrue("No Portuguese for:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun titlesAndNamesInsideMessagesAreNotTranslated() {
        I18n.lang = "pt"
        assertEquals("Abrindo Back to the Future na Netflix", I18n.t("Opening Back to the Future on Netflix"))
        assertEquals("Abrindo Fallout no Prime Video", I18n.t("Opening Fallout on Prime Video"))
        assertEquals("Barbie está na Max, que não é um dos seus serviços", I18n.t("Barbie is on Max, which isn't one of your services"))
        assertEquals("Abra o Globoplay uma vez pela TV para o controle aprender", I18n.t("Open Globoplay once from the TV, so the remote can learn it"))
        assertEquals("Comédia · ★ 7.8 · novidade", I18n.t("Comedy · ★ 7.8 · new"))
        assertEquals("Terça à noite · cerca de 1h15 livres", I18n.t("Tuesday evening · about 1 h 15 m free"))
        assertEquals("Hulu · filme · 1h40 · termina às 11:10", I18n.t("Hulu · movie · 1 h 40 m · ends 11:10"))
        assertEquals("Light & funny", I18n.english("Leve e divertido").let { I18n.lang = "pt"; it })
        assertEquals("Stranger Things", I18n.t("Stranger Things"))
        I18n.lang = "en"
        assertEquals("Opening Back to the Future on Netflix", I18n.t("Opening Back to the Future on Netflix"))
    }

    @Test fun portugueseVoiceCommands() {
        val p = CommandParser(emptyList())
        assertEquals(Command.Power(true), p.parse("ligar a TV"))
        assertEquals(Command.Power(false), p.parse("Desliga a televisão"))
        assertEquals(Command.SleepTimer(30), p.parse("desligar a TV em 30 minutos"))
        assertEquals(Command.SleepTimer(30), p.parse("desliga em meia hora"))
        assertEquals(Command.ScreenTimer(10), p.parse("timer de 10 minutos"))
        assertEquals(Command.CancelTimer, p.parse("cancelar o timer"))
        assertEquals(Command.VolumeSet(20), p.parse("volume 20"))
        assertEquals(Command.VolumeSet(15), p.parse("coloca o volume em 15"))
        assertTrue(p.parse("aumentar o volume") is Command.VolumeStep)
        assertEquals(false, (p.parse("abaixa o som") as Command.VolumeStep).up)
        assertEquals(Command.Mute(true), p.parse("mudo"))
        assertEquals(Command.Mute(false), p.parse("tirar o mudo"))
        assertEquals(Command.Media("pause"), p.parse("pausar"))
        assertEquals(Command.Media("play"), p.parse("continuar"))
        assertEquals(Command.Seek(30), p.parse("avançar 30 segundos"))
        assertEquals(Command.Seek(-10), p.parse("voltar 10 segundos"))
        assertEquals(Command.Button("BACK"), p.parse("voltar"))
        assertEquals(Command.Button("HOME"), p.parse("início"))
        assertEquals(Command.ChannelStep(true), p.parse("próximo canal"))
        assertEquals(Command.ChannelNumber("5"), p.parse("canal 5"))
        assertEquals(Command.Input(2), p.parse("HDMI 2"))
        assertEquals(Command.WhatToWatch(null), p.parse("o que vamos assistir hoje?"))
        assertEquals("netflix", (p.parse("abrir a Netflix") as Command.OpenApp).app.id)
        val s = p.parse("assistir Round 6 na Netflix") as Command.Search
        assertEquals("round 6", s.query); assertEquals("netflix", s.app?.id); assertTrue(s.title)
        assertEquals("the office", (p.parse("procurar the office no youtube") as Command.Search).query)
        // English still works exactly as before
        assertEquals(Command.Power(true), p.parse("turn on the tv"))
        assertEquals(Command.Seek(-10), p.parse("go back 10 seconds"))
    }

    @Test fun countries() {
        assertEquals("BR", Regions.fromPhone(java.util.Locale("pt", "BR")).code)
        assertEquals("US", Regions.fromPhone(java.util.Locale("de", "DE")).code)
        assertEquals("pt", Regions.resolve("auto", java.util.Locale("pt", "BR")))
        assertEquals("en", Regions.resolve("auto", java.util.Locale("en", "IN")))
        val br = com.home.tvremote.picks.Catalog.servicesFor("BR").map { it.name }
        assertTrue("Globoplay" in br && "Netflix" in br && "Hulu" !in br)
        val uk = com.home.tvremote.picks.Catalog.servicesFor("GB").map { it.name }
        assertTrue("BBC iPlayer" in uk && "ITVX" in uk && "Peacock" !in uk)
        assertTrue("JioHotstar" in com.home.tvremote.picks.Catalog.servicesFor("IN").map { it.name })
        assertTrue("Crave" in com.home.tvremote.picks.Catalog.servicesFor("CA").map { it.name })
        assertTrue("Stan" in com.home.tvremote.picks.Catalog.servicesFor("AU").map { it.name })
        I18n.country = "IN"; assertEquals("en-IN", I18n.speech)
        I18n.lang = "pt"; assertEquals("pt-BR", I18n.speech)
    }
}
