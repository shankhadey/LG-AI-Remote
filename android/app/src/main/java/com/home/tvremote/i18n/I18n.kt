package com.home.tvremote.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App text in the chosen language. The app is written in English; every string on screen, in a toast on the TV or in
 * a notification goes through [t], which returns the translation. Logs and Diagnostics stay in English.
 * Three layers:
 *  1. exact phrases,
 *  2. patterns for messages that carry names ("Opening {} on {}" → "Abrindo {1} {em2}"),
 *  3. lines made of parts joined by " · " ("Comedy · ★ 7.8 · new") are translated part by part.
 * Titles, names and brands are never translated.
 *
 * Portuguese (Brazil) style: "você", short and direct, sentence case, neutral voice ("Digitado", not "Digitei"),
 * Brazilian time ("1h30", "às 21:42"), and the right article with each brand ("na Netflix", "no Prime Video"):
 * in patterns, {em2} = "na/no" + name 2, {de1} = "da/do" + name 1, {o1} = "a/o" + name 1.
 * Coverage is checked by I18nTest, which scans the screens for strings that have no Portuguese.
 */
object I18n {
    /** "en" or "pt". Compose state, so the screens redraw when it changes. */
    var lang by mutableStateOf("en")
    /** The household's country (Regions code), for clocks and formats in the screens. */
    var country by mutableStateOf("US")
    /** Speech recognition locale: Brazilian Portuguese, or the country's English accent (en-IN, en-GB…). */
    val speech: String get() = if (lang == "pt") "pt-BR" else Regions.of(country).speech
    /** The app language's name, for AI prompts. */
    val aiLanguage: String get() = if (lang == "pt") "Brazilian Portuguese" else "English"

    fun t(s: String): String {
        if (lang == "en" || s.isBlank()) return s
        return translate(s) ?: s
    }

    /** Translation for the current language, or null if there is none (used by the coverage test). */
    fun translate(s: String): String? {
        if (lang == "en") return s
        PT[s]?.let { return it }
        for ((re, out) in PT_PATTERNS) re.matchEntire(s)?.let { m -> return fill(out, m.groupValues.drop(1)) }
        if (" · " in s && '\n' !in s) {
            val parts = s.split(" · ")
            val tr = parts.map { p -> PT[p] ?: PT_PATTERNS.firstNotNullOfOrNull { (re, out) -> re.matchEntire(p)?.let { fill(out, it.groupValues.drop(1)) } } }
            if (tr.any { it != null }) return parts.indices.joinToString(" · ") { tr[it] ?: parts[it] }
        }
        return null
    }

    /** Back to English, for logs (e.g. a label the TV page reports). Unknown text is returned as is. */
    fun english(s: String): String {
        if (lang == "en") return s
        REVERSE[s]?.let { return it }
        return if (" · " in s) s.split(" · ").joinToString(" · ") { REVERSE[it] ?: it } else s
    }
    private val REVERSE by lazy { PT.entries.associate { (k, v) -> v to k } }

    // ---- filling patterns ----
    private fun fill(out: String, args: List<String>): String {
        var r = out
        args.forEachIndexed { i, raw ->
            val a = brTime(raw)   // names and titles stay as they are; only "1 h 30 m" becomes "1h30"
            r = r.replace("{em${i + 1}}", em(a)).replace("{de${i + 1}}", de(a)).replace("{o${i + 1}}", art(a)).replace("{${i + 1}}", a)
        }
        return r
    }
    private fun brTime(s: String) = s.replace(Regex("^(\\d+) h (\\d+) m$"), "$1h$2").replace(Regex("^(\\d+) h$"), "$1h")

    /** Brands said with "a" in Brazil (a Netflix, a Max); the rest take "o" (o Prime Video, o Disney+, o Globoplay). */
    private val FEMININE = setOf("netflix", "max", "hbo max", "apple tv", "apple tv+", "crave", "bbc iplayer", "itvx", "sbs on demand", "abc iview")
    private fun feminine(name: String) = name.lowercase().trim() in FEMININE
    private fun em(name: String) = (if (feminine(name)) "na " else "no ") + name
    private fun de(name: String) = (if (feminine(name)) "da " else "do ") + name
    private fun art(name: String) = (if (feminine(name)) "a " else "o ") + name

    private fun pattern(en: String) = Regex(en.split("{}").joinToString("(.+?)") { Regex.escape(it) })

    private val DAYS = listOf("Monday" to "Segunda", "Tuesday" to "Terça", "Wednesday" to "Quarta", "Thursday" to "Quinta",
        "Friday" to "Sexta", "Saturday" to "Sábado", "Sunday" to "Domingo")
    private val PARTS = listOf("morning" to "de manhã", "afternoon" to "à tarde", "evening" to "à noite", "night" to "tarde da noite")

    val PT: Map<String, String> = buildMap {
        for ((d, dp) in DAYS) for ((p, pp) in PARTS) put("$d $p", "$dp $pp")
        putAll(mapOf(
            // ---- remote ----
            "No speech recognizer on this phone (install the Google app)" to "Este celular não tem reconhecimento de voz. Instale o app Google.",
            "Voice needs microphone permission" to "Para usar a voz, permita o acesso ao microfone",
            "Tap to pause · double-tap a side to skip" to "Toque para pausar · toque duas vezes nas laterais para pular",
            "Tap to pause/play · drag to point" to "Toque para pausar ou continuar · arraste para apontar",
            "Drag to point · tap to select" to "Arraste para apontar · toque para selecionar",
            "Back" to "Voltar", "BACK" to "VOLTAR", "Home" to "Início", "HOME" to "INÍCIO", "Timer" to "Timer", "Mute" to "Silenciar",
            "VOL" to "VOL", "VOLUME" to "VOLUME", "CH" to "CANAL", "Settings" to "Ajustes", "Turn on" to "Ligar", "Power" to "Ligar/desligar",
            "Turning on…" to "Ligando…", "Waking the TV" to "Ligando a TV", "or just say “turn on the TV”" to "ou diga “ligar a TV”",
            "Not it?" to "Não era isso?", "Dismiss" to "Fechar", "Type on the TV…" to "Digite na TV…",
            "Watching" to "Assistindo", "Talk" to "Falar", "Paused" to "Pausado", "Playing" to "Reproduzindo",
            "playing" to "reproduzindo", "paused" to "pausado",
            "Connecting…" to "Conectando…", "Not connected — tap to retry" to "Sem conexão. Toque para tentar de novo.",
            "Live TV" to "TV ao vivo", "Connected" to "Conectada", "No TV set up" to "Nenhuma TV configurada", "Off" to "Desligada",
            "Sound on" to "Som ativado", "Muted" to "Sem som", "Louder" to "Mais alto", "Quieter" to "Mais baixo",
            "Volume up" to "Aumentar volume", "Volume down" to "Diminuir volume",
            "Channel up" to "Próximo canal", "Channel down" to "Canal anterior", "Forward" to "Avançar",
            "Play" to "Assistir", "Pause" to "Pausar", "Stop" to "Parar", "Rewind" to "Voltar", "Fast forward" to "Avançar",
            "Thinking…" to "Pensando…", "Timer cancelled" to "Timer cancelado", "No timer is running" to "Nenhum timer ativo",
            "Checking the TV…" to "Verificando a TV…", "Turning the screen on" to "Ligando a tela", "Turning off" to "Desligando",
            "TV is on" to "TV ligada", "The TV didn't respond" to "A TV não respondeu",
            "Opened Home instead of Live TV" to "Aberto o Início em vez da TV ao vivo",
            "Phone remote connected" to "Controle do celular conectado",
            "the TV accepted the request but didn't open it" to "a TV aceitou o pedido, mas não abriu",
            "The TV didn't open the picks page (details in Diagnostics)" to "A TV não abriu a página de sugestões. Veja os detalhes em Diagnóstico.",
            "That doesn't look like a MAC address (e.g. A8:23:FE:01:02:03)" to "Isso não parece um endereço MAC (ex.: A8:23:FE:01:02:03)",
            "Saved. Power on is ready." to "Salvo. Já dá para ligar a TV pelo celular.",
            "To turn the TV on I need its MAC address — add it from the menu under the TV name." to "Para ligar a TV, é preciso o endereço MAC dela. Adicione no menu do nome da TV.",
            "The TV didn't respond to the wake-up signal. See Power-on settings (menu under the TV name) for what to check." to "A TV não respondeu ao sinal para ligar. Veja o que conferir em Ligar pelo celular (menu do nome da TV).",
            "Allow the microphone first (tap the remote's mic once)" to "Permita o microfone primeiro: toque uma vez no microfone do controle",
            "The phone isn't on Wi-Fi, so the TV can't show the picks" to "O celular não está no Wi-Fi, então a TV não consegue mostrar as sugestões",
            "No TV found." to "Nenhuma TV encontrada.",
            " If the TV's address isn't in that range, the phone and TV are on different networks (e.g. a guest network). You can still enter the TV's IP below." to
                " Se o endereço da TV não estiver nessa faixa, o celular e a TV estão em redes diferentes (como uma rede de visitantes). Você ainda pode digitar o IP da TV abaixo.",
            // ---- settings ----
            "This TV" to "Esta TV", "Name" to "Nome", "Turn on from the phone" to "Ligar pelo celular", "Ready" to "Pronto", "Set up" to "Configurar",
            "After turning on" to "Ao ligar", "Home screen" to "Tela inicial", "Last input" to "Última entrada",
            "Picks" to "Sugestões", "People" to "Pessoas", "Add" to "Adicionar", "Favourites" to "Favoritos", "Services" to "Serviços",
            "Bedtimes" to "Hora de dormir", "More" to "Mais", "Help & diagnostics" to "Ajuda e diagnóstico", "Advanced" to "Avançado",
            "keys, AI" to "chaves, IA", "If something isn't right" to "Se algo não estiver certo",
            "Taps don't select on the TV" to "O toque não seleciona na TV", "Fix touchpad tap" to "Ajustar o toque",
            "Shows open on the app's home" to "Os títulos abrem no início do app", "Test opening a show" to "Testar abrir um título",
            "The remote lost the TV" to "O controle perdeu a conexão", "Reconnect" to "Reconectar", "Something went wrong" to "Algo deu errado",
            "Share diagnostics" to "Enviar diagnóstico", "This phone" to "Este celular", "Use a different TV" to "Usar outra TV",
            "Language & country" to "Idioma e país", "Language" to "Idioma", "Country" to "País", "App language" to "Idioma do app",
            "Automatic" to "Automático", "English" to "Inglês", "Português (Brasil)" to "Português (Brasil)",
            "United States" to "Estados Unidos", "Brazil" to "Brasil", "India" to "Índia", "Canada" to "Canadá",
            "United Kingdom" to "Reino Unido", "Australia" to "Austrália",
            "Where you watch: sets the streaming services, where-to-watch info and the clock." to "Onde você assiste. Define os serviços de streaming, onde cada título está disponível e o formato da hora.",
            "Follows the phone's language." to "Automático segue o idioma do celular.",
            // ---- timer ----
            "TV off in" to "TV desliga em", "Timer on TV" to "Timer na TV", "+10 min" to "+10 min", "Cancel timer" to "Cancelar timer",
            "Turn TV off in…" to "Desligar a TV em…", "Reminders pop up on the TV at 10, 5 and 1 min." to "A TV avisa quando faltarem 10, 5 e 1 min.",
            "Show a timer on TV" to "Mostrar um timer na TV",
            "Full-screen countdown. Back on the remote returns to what you were watching." to "Contagem regressiva em tela cheia. O botão Voltar do controle retorna ao que você assistia.",
            "Or just say it: “turn off in 40 minutes”, “timer 10 minutes”" to "Ou diga: “desligar em 40 minutos”, “timer de 10 minutos”",
            "Time's up" to "Tempo esgotado", "TV turns off in 1 minute" to "A TV desliga em 1 minuto", "Timer stopped" to "Timer parado",
            "Press Back to return" to "Aperte Voltar para retornar",
            "The phone isn't on Wi-Fi, so the TV can't show the timer" to "O celular não está no Wi-Fi, então a TV não consegue mostrar o timer",
            "The TV didn't open the timer screen (details in Diagnostics). The timer is still running on your phone." to "A TV não abriu a tela do timer (detalhes em Diagnóstico). O timer continua no seu celular.",
            // ---- voice ----
            "Listening" to "Ouvindo", "Listening…" to "Ouvindo…", "Stops when you pause · tap anywhere to cancel" to "Para quando você faz uma pausa · toque em qualquer lugar para cancelar",
            "Say something…" to "Fale algo…", "Long requests are fine — take your time." to "Pode pedir com calma, frases longas funcionam.",
            "“Open Netflix”" to "“Abrir a Netflix”", "“Volume 20”" to "“Volume 20”", "“Turn off in 30 minutes”" to "“Desligar em 30 minutos”", "“Pause”" to "“Pausar”",
            "Done" to "Pronto",
            // ---- dialogs ----
            "Open shows directly" to "Abrir títulos direto", "in use" to "em uso",
            "A · Remote's OK button (default)" to "A · Botão OK do controle (padrão)", "B · Pointer click" to "B · Clique do ponteiro", "C · Keyboard Enter" to "C · Enter do teclado",
            "Make taps select" to "Fazer o toque selecionar",
            "1. Close this, drag the cursor onto an app tile on the TV, then reopen it (tap the TV name › Fix touchpad tap)." to "1. Feche esta janela, arraste o cursor até um app na TV e abra de novo (nome da TV › Ajustar o toque).",
            "2. Tap A, B and C in turn. Stop at the one that opens the tile — it's saved as what a tap does." to "2. Toque em A, B e C, um de cada vez. Pare no que abrir o app: ele fica salvo como o seu toque.",
            "Name this TV" to "Dê um nome a esta TV", "e.g. Living Room" to "ex.: Sala", "Save" to "Salvar", "Cancel" to "Cancelar",
            "Diagnostics" to "Diagnóstico",
            "What the app asked the TV, and what it answered (newest first). Copy and send this to whoever is helping you." to "O que o app pediu à TV e o que ela respondeu, do mais recente ao mais antigo. Copie e envie para quem estiver ajudando você.",
            "Copied" to "Copiado", "Copy" to "Copiar", "Share" to "Compartilhar", "Diagnostics cleared — test, then Share" to "Diagnóstico limpo. Teste e depois toque em Compartilhar.",
            "Start fresh" to "Começar do zero", "Close" to "Fechar", "Turning the TV on" to "Ligar a TV pelo celular",
            "1. On the TV, turn on Settings › General › Devices › External Devices › TV On With Mobile › Turn on via Wi-Fi." to "1. Na TV, ative Configurações › Geral › Dispositivos › Dispositivos externos › Ligar TV pelo celular › Ligar via Wi-Fi.",
            "If it still won't wake: TVs on Wi-Fi can switch Wi-Fi fully off in standby, and some routers and mesh systems don't pass the wake-up signal. An Ethernet cable to the TV is the most reliable fix." to
                "Se ainda não ligar: algumas TVs desligam o Wi-Fi em standby, e alguns roteadores e sistemas mesh não repassam o sinal para ligar. Um cabo de rede na TV é a solução mais confiável.",
            "2. The app wakes the TV using its MAC addresses. A TV on Wi-Fi needs its Wi-Fi address; on a cable, its wired one. Both are filled in automatically when the TV allows it — or enter them yourself, separated by a comma. They're on the TV under Settings › General › About This TV (or Network › Wi-Fi › Advanced)." to
                "2. O app liga a TV usando os endereços MAC dela. Uma TV no Wi-Fi precisa do endereço Wi-Fi; no cabo, do endereço com fio. Os dois são preenchidos automaticamente quando a TV permite. Você também pode digitar, separados por vírgula. Eles ficam na TV em Configurações › Geral › Sobre esta TV (ou Rede › Wi-Fi › Avançado).",
            "Wi-Fi MAC, wired MAC" to "MAC do Wi-Fi, MAC com fio",
            // ---- setup ----
            "Find your TV" to "Encontre sua TV", "Turn the TV on and make sure this phone is on the same Wi-Fi." to "Ligue a TV e confira se o celular está no mesmo Wi-Fi.",
            "Searching your Wi-Fi…" to "Procurando no seu Wi-Fi…", "Search again" to "Procurar de novo",
            "Or enter its IP address (TV Settings › Network › Wi-Fi › Advanced)" to "Ou digite o endereço IP (na TV: Configurações › Rede › Wi-Fi › Avançado)",
            "Connect" to "Conectar", "Look at your TV" to "Olhe para a TV",
            "A message is asking to allow \"Phone remote\". Select Allow with the TV's own remote — you only do this once." to "Uma mensagem pede para permitir o \"Phone remote\". Escolha Permitir com o controle da TV. Isso só é feito uma vez.",
            "A message is asking to allow \"TV Remote\". Select Accept with the LG remote — you only do this once." to "Uma mensagem pede para permitir o \"TV Remote\". Escolha Aceitar com o controle da LG. Isso só é feito uma vez.",
            // ---- picks ----
            "What should we watch?" to "O que vamos assistir?", "Finding picks…" to "Buscando sugestões…", "Try something else" to "Tentar outra coisa",
            "On TV" to "Na TV", "Not on a button" to "Nenhum botão selecionado", "Pointing at: " to "Apontando para: ", "Look at the TV" to "Olhe para a TV",
            "Move to point · tap to click" to "Mova para apontar · toque para clicar", "Say it" to "Falar",
            "Who's watching" to "Quem vai assistir", "Add favourites" to "Adicionar favoritos", "Later" to "Depois", "Any" to "Todos",
            "Keep watching" to "Continuar assistindo", "Don't suggest this" to "Não sugerir mais", "Mood" to "Clima",
            "Anything good" to "Qualquer coisa boa", "Never" to "Nunca", "Not now" to "Agora não", "NEW" to "NOVO",
            "  Listening… tap to stop" to "  Ouvindo… toque para parar", "  or say it — “something like Severance”" to "  ou diga: “algo como Ruptura”",
            "Tap to play · swipe left for “not now” · hold for “never”" to "Toque para assistir · deslize para a esquerda para “agora não” · segure para “nunca”",
            "Other mood" to "Outro clima", "Data from TMDB · where to watch by JustWatch" to "Dados do TMDB · onde assistir: JustWatch",
            "Shows you loved" to "Títulos que você amou", "Type a title…" to "Digite um título…", "Searching…" to "Procurando…",
            "Nothing found. Check the spelling, or the TMDB key in Picks settings." to "Nada encontrado. Confira a grafia ou a chave do TMDB nos ajustes de Sugestões.",
            "Series" to "Série", "Movie" to "Filme", "series" to "série", "movie" to "filme", "Added" to "Adicionado",
            "Set up picks" to "Configurar sugestões", "Change any time from the TV name menu." to "Dá para mudar quando quiser no menu do nome da TV.",
            "Who uses this remote?" to "Quem usa este controle?", "Add a person (e.g. a first name)" to "Adicione uma pessoa (ex.: o primeiro nome)",
            "Your services" to "Seus serviços", "Languages you watch" to "Idiomas que vocês assistem", "Usually in bed by" to "Costumam dormir às",
            "Sun–Thu" to "Dom–Qui", "Fri–Sat" to "Sex–Sáb", "None yet: tap to add" to "Nenhum ainda. Toque para adicionar.",
            "Choose at least one service" to "Escolha pelo menos um serviço", "A TMDB key is needed to find shows" to "É preciso uma chave do TMDB para encontrar títulos",
            "Paste your Groq key, or choose another option" to "Cole sua chave do Groq ou escolha outra opção",
            "Finding shows" to "Busca de títulos", "Who makes the picks?" to "Quem faz as sugestões?", "TMDB key" to "Chave do TMDB",
            "Needed to find shows and where they're streaming. Free at themoviedb.org › Settings › API." to "Necessária para encontrar títulos e saber onde estão disponíveis. Grátis em themoviedb.org › Settings › API.",
            "This phone's AI" to "IA do celular", "Private and free — nothing leaves your phone. " to "Privada e grátis: nada sai do seu celular. ",
            "Checking…" to "Verificando…", "Groq (online AI)" to "Groq (IA online)",
            "A bigger, cleverer AI. Needs a free key from console.groq.com." to "Uma IA maior e mais esperta. Precisa de uma chave grátis de console.groq.com.",
            "Groq key" to "Chave do Groq", "Paste the key from console.groq.com › API Keys." to "Cole a chave de console.groq.com › API Keys.",
            "No AI" to "Sem IA", "Simple rules: popular, well-rated shows that fit your mood and time." to "Regras simples: títulos populares e bem avaliados que combinam com o clima e o tempo que vocês têm.",
            "If your choice isn't available at that moment, the app uses the next option automatically, so you always get picks. Keys stay on this phone." to
                "Se a opção escolhida não estiver disponível, o app usa a próxima automaticamente, então sempre há sugestões. As chaves ficam só neste celular.",
            "Saved ✓" to "Salvo ✓", "Paste key" to "Colar chave", "No more picks" to "Sem mais sugestões", "Skipped for now" to "Fica para depois",
            "Together" to "Juntos", "Surprise us" to "Surpreenda a gente", "What should I watch?" to "O que eu assisto?",
            "Matches what you described." to "Combina com o que você descreveu.", "Nothing like that on your services" to "Nada parecido nos seus serviços",
            "Set up picks first" to "Configure as sugestões primeiro", "Not it? Search Netflix for it" to "Não era isso? Procurar na Netflix",
            "Not it? Try another way" to "Não era isso? Tentar de outro jeito", "Waiting for a mood…" to "Esperando um clima…",
            "Nothing matched — try another mood, language or “Any”" to "Nada combinou. Tente outro clima, outro idioma ou “Todos”.",
            "Double-tap to go back 10 seconds" to "Toque duas vezes para voltar 10 segundos", "Double-tap to skip 10 seconds" to "Toque duas vezes para avançar 10 segundos",
            "Fix" to "Corrigir", "Couldn't find anything with both" to "Nada encontrado com os dois juntos",
            "Use a different TV?" to "Usar outra TV?", "Find TVs" to "Procurar TVs",
            // onboarding: welcome, turn-on help, nothing found
            "Your phone is the remote" to "Seu celular é o controle", "Find my TV" to "Encontrar minha TV",
            "Controls your LG or Samsung TV over Wi-Fi" to "Controla sua TV LG ou Samsung pelo Wi-Fi",
            "Point, tap, type and turn it on or off." to "Aponte, toque, digite e ligue ou desligue a TV.",
            "Just say it" to "É só falar", "“Open Netflix”, “volume 20”, “turn off in 30 minutes”." to "“Abrir a Netflix”, “volume 20”, “desligar em 30 minutos”.",
            "Knows what to watch" to "Sabe o que assistir", "Five picks from your services, in seconds." to "Cinco sugestões dos seus serviços, em segundos.",
            "The TV and this phone need to be on the same Wi-Fi." to "A TV e o celular precisam estar no mesmo Wi-Fi.",
            "The TV didn't turn on" to "A TV não ligou",
            "Your phone can turn the TV on once one setting is switched on, on the TV. You only do this once." to
                "O celular consegue ligar a TV depois que você ativa um ajuste na própria TV. Isso só precisa ser feito uma vez.",
            "Turn the TV on, then go to" to "Ligue a TV e vá em",
            "Settings › General › Devices › External Devices › TV On With Mobile › Turn on via Wi-Fi" to
                "Configurações › Geral › Dispositivos › Dispositivos externos › Ligar TV com o celular › Ligar via Wi-Fi",
            "Settings › General › Network › Expert Settings › Power On with Mobile" to "Configurações › Geral › Rede › Configurações avançadas › Ligar com o celular",
            "Switch it on. Menu names vary a little by TV year." to "Ative essa opção. Os nomes dos menus mudam um pouco conforme o ano da TV.",
            "Still not turning on?" to "Ainda não liga?",
            "TVs on Wi-Fi can switch Wi-Fi off in standby. A network cable to the TV is the most reliable fix." to
                "TVs no Wi-Fi podem desligar o Wi-Fi em standby. Um cabo de rede na TV é a solução mais confiável.",
            "Some routers and mesh systems don't pass the wake-up signal on to the TV." to "Alguns roteadores e sistemas mesh não repassam o sinal para ligar a TV.",
            "Check the TV's MAC address in Settings › Turn on from the phone." to "Confira o endereço MAC da TV em Ajustes › Ligar pelo celular.",
            "Try again" to "Tentar de novo", "Turning it on with the TV's remote always works; the app follows." to "Ligar pelo controle da TV sempre funciona, e o app acompanha.",
            "No TV found yet" to "Nenhuma TV encontrada", "Check these, then search again:" to "Confira estes pontos e procure de novo:",
            "The TV is on" to "A TV está ligada", "Not in standby: the picture is showing." to "Fora do standby, com imagem na tela.",
            "Same Wi-Fi" to "Mesmo Wi-Fi", "This phone and the TV are on the same network, not a guest network." to "O celular e a TV estão na mesma rede, e não numa rede de visitantes.",
            "Devices can see each other" to "Os aparelhos se enxergam na rede",
            "Some routers block this (\"AP isolation\" or \"client isolation\"). Turning it off helps." to "Alguns roteadores bloqueiam isso (\"isolamento de AP\" ou \"de clientes\"). Desativar ajuda.",
            "Enter the TV's IP address" to "Digitar o IP da TV", "Details" to "Detalhes",
            "This phone isn't on Wi-Fi, so it can't turn the TV on. Connect to your home Wi-Fi and try again." to
                "Este celular não está no Wi-Fi, então não consegue ligar a TV. Conecte-se ao Wi-Fi de casa e tente de novo.",
            "This phone is on a different network from the TV. Connect to the same Wi-Fi as the TV and try again." to
                "Este celular está em outra rede, diferente da TV. Conecte-se ao mesmo Wi-Fi da TV e tente de novo.",
            "The TV's network connection is unstable. If this keeps happening, restart the TV fully (turn off Quick Start+, then off and on, or unplug it for a minute)." to
                "A conexão de rede da TV está instável. Se isso continuar, reinicie a TV por completo (desative o Início rápido+ e desligue e ligue, ou tire da tomada por um minuto).",
            "This phone may be on a different network from the TV. Trying anyway." to "Este celular pode estar em outra rede, diferente da TV. Tentando mesmo assim.",
            "About" to "Sobre",
            "This product uses the TMDB API but is not endorsed or certified by TMDB." to "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.",
            "Where-to-watch data by JustWatch." to "Dados de onde assistir fornecidos pelo JustWatch.",
            "Not affiliated with LG, Samsung or any streaming service." to "Sem vínculo com LG, Samsung ou qualquer serviço de streaming.",
            // privacy
            "Privacy" to "Privacidade", "Share anonymous usage" to "Compartilhar uso anônimo", "Yes" to "Sim", "No" to "Não", "Not set" to "Não definido",
            "Share" to "Compartilhar", "Don't share" to "Não compartilhar", "No thanks" to "Não, obrigado",
            "Help improve TV Remote?" to "Quer ajudar a melhorar o TV Remote?",
            "Share anonymous usage: which features get used, and in which country. Never names, titles or what you say." to
                "Compartilhe dados de uso anônimos: quais recursos são usados e em qual país. Nunca nomes, títulos ou o que você fala.",
            "Helps us see which features get used and in which countries. Never names, show titles, what you say or your TV's address. You can change this any time." to
                "Ajuda a entender quais recursos são usados e em quais países. Nunca nomes, títulos, o que você fala ou o endereço da sua TV. Dá para mudar quando quiser.",
            // the picks page on the TV
            "Choosing on the phone…" to "Escolhendo no celular…", "Pick a mood" to "Escolha um clima", "Show 5 more" to "Mostrar mais 5",
            "Back to moods" to "Voltar aos climas", "Point with your phone · tap to choose" to "Aponte com o celular · toque para escolher", "Data from TMDB · where to watch by JustWatch" to "Dados do TMDB · onde assistir: JustWatch",
            // moods, genres, reasons, time
            "New" to "Novidades", "Out this year" to "Lançados este ano", "Light & funny" to "Leve e divertido", "Easy, no thinking" to "Fácil, sem pensar",
            "Cozy" to "Aconchegante", "Warm, comforting" to "Para relaxar e se sentir bem", "Gripping" to "Eletrizante", "Can't look away" to "Impossível parar de ver",
            "Mind-bending" to "Para pensar", "Twists, big ideas" to "Reviravoltas e grandes ideias", "Anything" to "Qualquer coisa",
            "Comedy" to "Comédia", "Drama" to "Drama", "Crime" to "Crime", "Mystery" to "Mistério", "Thriller" to "Suspense", "Sci-fi" to "Ficção científica",
            "Sci-fi & fantasy" to "Ficção e fantasia", "Fantasy" to "Fantasia", "Romance" to "Romance", "Family" to "Família", "Animation" to "Animação",
            "Action" to "Ação", "Documentary" to "Documentário", "Horror" to "Terror", "Adventure" to "Aventura",
            "new" to "novidade", "popular right now" to "em alta agora", "past your usual bedtime" to "já passou da hora de dormir", "plenty of time" to "tempo de sobra",
            "Bengali" to "Bengali", "Hindi" to "Hindi", "Tamil" to "Tâmil", "Telugu" to "Télugo", "Spanish" to "Espanhol",
            "Korean" to "Coreano", "Japanese" to "Japonês", "French" to "Francês", "Portuguese" to "Português", "Malayalam" to "Malaiala",
        ))
    }

    /** Messages with names in them. "{}" in the English; "{1}", "{em1}" ("na Netflix"), "{de1}" ("da Netflix") in the Portuguese. */
    private val PT_PATTERNS: List<Pair<Regex, String>> = listOf(
        "Still waking the TV · {} s" to "Ainda ligando a TV · {1} s",
        "Opened {}" to "Aberto: {1}", "ends {}" to "termina às {1}", "{} down" to "{1} −", "{} up" to "{1} +",
        "Each try opens a different title's own page in {}. Press Home on the TV between tries and note which ones land on that title. The last one you tap is used." to
            "Cada tentativa abre a página de um título diferente {em1}. Aperte Início na TV entre uma e outra e veja quais abrem o título certo. A última que você tocar fica em uso.",
        "Try {} · {}" to "Tentativa {1} · {2}", "Sent {} — did the tile open?" to "{1} enviado. O app abriu?",
        "Picks get better when I know {}'s taste" to "As sugestões melhoram quando conheço o gosto de {1}",
        "Name 3–5 shows or movies {} loved." to "Diga de 3 a 5 séries ou filmes que {1} amou.",
        "Surprise {}" to "Surpreenda {1}", "Never suggest {}?" to "Nunca sugerir {1}?", "It won't come up again for {}." to "Não vai aparecer de novo para {1}.",
        "{} more" to "Mais {1}", "{}'s favourites" to "Favoritos de {1}", "Shows or movies {} loved. 3 is enough." to "Séries ou filmes que {1} amou. 3 já bastam.",
        "Done · {} added" to "Pronto · {1} adicionados", "Connected to {}" to "Conectado a {1}", "Typed “{}”" to "Digitado: “{1}”",
        "Forward {}" to "Avançar {1}", "Back {}" to "Voltar {1}", "{} min {} s" to "{1} min {2} s",
        "{} is on {} — not one of your services" to "{1} está {em2}, que não é um dos seus serviços",
        "Opening {}" to "Abrindo {1}",
        "The TV isn't responding to opening {}. Try it with the TV remote; if it won't open there either, restart the TV or update {}." to
            "A TV não está respondendo ao abrir {1}. Tente pelo controle da TV. Se também não abrir, reinicie a TV ou atualize o app {2}.",
        "{} didn't open: {}" to "{1} não abriu: {2}",
        "Saved {} addresses. Power on will try all of them." to "{1} endereços salvos. Ao ligar, o app tenta todos.",
        "This phone couldn't send the wake-up signal ({}). Make sure the phone is on Wi-Fi." to "O celular não conseguiu enviar o sinal para ligar ({1}). Confira se ele está no Wi-Fi.",
        "TV turns off in {} min" to "A TV desliga em {1} min", "TV turns off in {} minutes" to "A TV desliga em {1} minutos",
        "{}-minute timer on the TV" to "Timer de {1} minutos na TV",
        "Typed \"{}\"" to "Digitado: \"{1}\"", "{}. To search, say “search for …”" to "{1}. Para procurar, diga “procurar …”",
        "Volume {}" to "Volume {1}", "Channel {}" to "Canal {1}", "HDMI {}" to "HDMI {1}",
        "Looking for {}…" to "Procurando {1}…", "{} is on {} — open {} once on the TV" to "{1} está {em2}. Abra {o3} uma vez na TV para o controle aprender.",
        "“{}” isn't a show or film — searching YouTube" to "“{1}” não é série nem filme. Procurando no YouTube.",
        "Couldn't find an app or a show called “{}” on your services" to "Nenhum app ou título chamado “{1}” nos seus serviços",
        "Added {} minutes" to "Mais {1} minutos", "Searching YouTube for \"{}\"" to "Procurando \"{1}\" no YouTube",
        "Opened {}. Select its search box and “{}” is typed for you." to "Aberto: {1}. Selecione a busca e “{2}” será digitado.",
        "Couldn't get picks ({}). Check your internet and keys." to "Não foi possível buscar sugestões ({1}). Confira a internet e as chaves.",
        "Won't suggest {} for {} again" to "Não vamos mais sugerir {1} para {2}",
        "Couldn't tell which app has {}" to "Não foi possível saber em qual app está {1}",
        "Open {} once from the TV, so the remote can learn it" to "Abra {o1} uma vez pela TV para o controle aprender",
        "Opening {} on {}" to "Abrindo {1} {em2}", "Select Search — the remote will type “{}”" to "Selecione Buscar. O controle vai digitar “{1}”.",
        "Opening {}. Select its search box and “{}” is typed for you." to "Abrindo {1}. Selecione a busca e “{2}” será digitado.",
        "Not it? Type “{}” in {}'s search" to "Não era isso? Digitar “{1}” na busca {de2}",
        "Select {}'s search box: “{}” is typed as soon as it opens" to "Selecione a busca {de1}: “{2}” será digitado assim que ela abrir",
        "Didn't understand “{}”" to "Não entendi “{1}”", "Couldn't find “{}”" to "“{1}” não encontrado",
        "Nothing with {} on your services right now" to "Nada com {1} nos seus serviços agora", "Nothing with {} on your services" to "Nada com {1} nos seus serviços",
        "With {}." to "Com {1}.", "With {}" to "Com {1}",
        "{} is on {}, but the remote hasn't seen the {} app on this TV yet — open {} once from the TV, then ask again" to
            "{1} está {em2}, mas o controle ainda não viu o app {3} nesta TV. Abra o app uma vez pela TV e peça de novo.",
        "{} is on {}, which isn't one of your services" to "{1} está {em2}, que não é um dos seus serviços",
        "{} is on {}, which isn't one of your services — add {} in Picks settings if you have it" to
            "{1} está {em2}, que não é um dos seus serviços. Se você assina, adicione nos ajustes de Sugestões.",
        "{} isn't on a subscription service in {} (only to rent or buy)" to "{1} não está em nenhum serviço de assinatura no país (só para alugar ou comprar)",
        "Couldn't find titles with {}" to "Nenhum título encontrado com {1}",
        "Fans of {} also loved it." to "Quem gosta de {1} também adorou.",
        "about {} free" to "cerca de {1} livres",
        "None of your services were found in TMDB's {} list" to "Nenhum dos seus serviços aparece na lista do TMDB para este país",
        "TV turns off at {}" to "A TV desliga às {1}", "Timer on TV · ends at {}" to "Timer na TV · termina às {1}",
        "The remote looks for TVs on your Wi-Fi. {} stays set up until the new TV is connected, and Back returns to it." to
            "O controle vai procurar TVs no seu Wi-Fi. A {1} continua configurada até a nova TV ser conectada, e Voltar retorna a ela.",
        "Opening {} — look for {}" to "Abrindo {1}. Procure {2}.", "Fri–Sat {}" to "Sex–Sáb {1}",
        "{} h {} m" to "{1}h{2}", "{} h" to "{1}h",
    ).sortedByDescending { it.first.replace("{}", "").length }   // most specific first ("Opening {} on {}" before "Opening {}")
        .map { (en, pt) -> pattern(en) to pt }
}
