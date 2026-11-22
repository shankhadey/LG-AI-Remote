package com.home.tvremote.voice

/**
 * Brazilian Portuguese voice commands, rewritten into the English phrases [CommandParser] understands, so all the
 * parser's rules (and their tests) apply. Input is already normalized: lowercase, accents removed ("próximo" →
 * "proximo"), punctuation stripped. Titles, names and app names pass through unchanged ("assistir round 6" →
 * "play round 6"). English input is returned as is.
 */
object Portuguese {
    private val TV = "(?: (?:a |na )?(?:tv|televisao))?"
    private val DUR = "(\\d+) ?(minutos?|min|horas?)|(meia hora)|(uma hora)"

    private fun dur(m: MatchResult, from: Int): String {
        val g = m.groupValues
        return when {
            g[from].isNotEmpty() -> g[from] + if (g[from + 1].startsWith("h")) " hours" else " minutes"
            g[from + 2].isNotEmpty() -> "half an hour"
            else -> "an hour"
        }
    }

    /** Whole-phrase rules, in order. */
    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // timers (before power)
        Regex("(?:desligar|desliga|desligue|apagar|apaga)$TV (?:em|daqui a|dentro de) (?:$DUR)") to { m -> "turn off the tv in " + dur(m, 1) },
        Regex("(?:timer|temporizador|cronometro)(?: de| para)? (?:$DUR)(?: na tv)?") to { m -> "timer " + dur(m, 1) },
        Regex("(?:cancelar|cancela|cancele|parar|para|desligar) (?:o )?(?:timer|temporizador)") to { _ -> "cancel timer" },
        Regex("mais (\\d+) minutos?") to { m -> "${m.groupValues[1]} more minutes" },
        // power
        Regex("(?:ligar|liga|ligue|acender|acende)$TV") to { _ -> "turn on the tv" },
        Regex("(?:desligar|desliga|desligue|apagar|apaga)$TV") to { _ -> "turn off the tv" },
        // volume
        Regex("(?:colocar |coloca |poe |por )?(?:o )?volume (?:em |no |para |pra )?(\\d+)") to { m -> "volume ${m.groupValues[1]}" },
        Regex("(?:aumentar|aumenta|aumente|subir|sobe|suba)(?: o)? (?:volume|som)(?: um pouco)?|mais alto|aumenta ai") to { _ -> "louder" },
        Regex("(?:diminuir|diminui|diminua|abaixar|abaixa|abaixe|baixar|baixa|baixe)(?: o)? (?:volume|som)(?: um pouco)?|mais baixo") to { _ -> "quieter" },
        Regex("(?:tirar o mudo|tira o mudo|ativar o som|ativa o som|liga o som|ligar o som|com som|desmutar)") to { _ -> "unmute" },
        Regex("(?:mudo|silenciar|silencia|silencio|tirar o som|tira o som|sem som|mutar|muta)") to { _ -> "mute" },
        // playback
        Regex("(?:pausar|pausa|pause|da pausa|dar pausa)") to { _ -> "pause" },
        Regex("(?:continuar|continua|continue|retomar|retoma|despausar|reproduzir|play|da play|dar play)") to { _ -> "play" },
        Regex("(?:parar|para|pare)") to { _ -> "stop" },
        Regex("(?:avancar|avanca|avance|pular|pula|pule|adiantar|adianta) (\\d+) ?(segundos?|seg|minutos?|min)") to { m ->
            "skip ${m.groupValues[1]} " + if (m.groupValues[2].startsWith("m")) "minutes" else "seconds" },
        Regex("(?:voltar|volta|volte|retroceder|retrocede|atrasar) (\\d+) ?(segundos?|seg|minutos?|min)") to { m ->
            "back ${m.groupValues[1]} " + if (m.groupValues[2].startsWith("m")) "minutes" else "seconds" },
        // navigation
        Regex("(?:voltar|volta|volte|retornar)") to { _ -> "back" },
        Regex("(?:inicio|tela inicial|pagina inicial|menu inicial|ir para o inicio|home)") to { _ -> "home" },
        Regex("(?:ok|confirmar|confirma|selecionar|seleciona|escolher|entrar)") to { _ -> "ok" },
        Regex("(?:para )?(?:cima|sobe)") to { _ -> "up" }, Regex("(?:para )?baixo|desce") to { _ -> "down" },
        Regex("(?:para a )?esquerda") to { _ -> "left" }, Regex("(?:para a )?direita") to { _ -> "right" },
        Regex("(?:configuracoes|ajustes|menu)") to { _ -> "menu" },
        // channels and inputs
        Regex("(?:proximo canal|canal acima|sobe (?:o )?canal|subir (?:o )?canal|canal seguinte)") to { _ -> "channel up" },
        Regex("(?:canal anterior|canal abaixo|desce (?:o )?canal|descer (?:o )?canal)") to { _ -> "channel down" },
        Regex("(?:ir para o |muda para o |mudar para o |coloca no |colocar no )?canal (\\d+(?:[ .-]\\d+)?)") to { m -> "channel ${m.groupValues[1]}" },
        Regex("(?:entrada |mudar para |muda para )?(?:o )?hdmi ?(\\d)") to { m -> "hdmi ${m.groupValues[1]}" },
        // what to watch
        Regex("o que (?:vamos|vou|eu|a gente|nos|devemos|devo|podemos|posso)? ?(?:assistir|ver)(?: hoje| agora| hoje a noite)?|me (?:sugere|sugira|recomenda|recomende) algo|(?:sugestoes|sugestao|recomendacoes)") to
            { _ -> "what should we watch" },
        Regex("(?:me |nos )?(?:sugere|sugira|recomenda|recomende|escolhe|escolha|acha|ache) (?:um |uma |algo|alguma coisa)(.*)") to { m -> "suggest something" + m.groupValues[1] },
        // search and play (the name passes through)
        Regex("(?:procurar|procura|procure|buscar|busca|busque|pesquisar|pesquisa|pesquise)(?: por)? (.+?) (?:no|na|em) (.+)") to { m -> "search ${m.groupValues[1]} on ${m.groupValues[2]}" },
        Regex("(?:procurar|procura|procure|buscar|busca|busque|pesquisar|pesquisa|pesquise)(?: por)? (.+)") to { m -> "search for ${m.groupValues[1]}" },
        Regex("(?:quero (?:ver|assistir)|vamos (?:ver|assistir)|assistir(?: a| o)?|assiste|colocar|coloca|coloque|bota|botar|passar|passa|tocar|toca|toque|reproduzir|ver) (.+?)(?: (?:no|na|pelo|pela) (netflix|youtube|prime video|prime|disney plus|disney|max|globoplay|apple tv))?") to
            { m -> "play ${m.groupValues[1]}" + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { " on $it" } ?: "") },
        Regex("(?:abrir|abre|abra|abrir o|abrir a|abre o|abre a|abra o|abra a|entrar no|entrar na|entra no|entra na|ir para o|ir para a|vai para o|vai para a|iniciar|inicia) (.+?)(?: app| aplicativo)?") to
            { m -> "open ${m.groupValues[1].removePrefix("o ").removePrefix("a ")}" },
    )

    fun toEnglish(t: String): String {
        for ((re, out) in RULES) re.matchEntire(t)?.let { return out(it) }
        return t
    }

    /** Portuguese words that mean "they want picks" (in addition to the English ones). */
    val WANTS_PICKS = Regex("\\b(filmes?|series?|algo|alguma coisa|comedias?|suspense|terror|documentarios?|drama|romance|desenhos?|novelas?)\\b", RegexOption.IGNORE_CASE)
}
