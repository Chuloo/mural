package chat.mural.core

/**
 * Language Modules this platform offers on top of the generated cross-platform registry.
 *
 * `Languages.kt` is generated from the iPhone language content, so a Language Module that ships on
 * Android first lives here until its Swift module exists. Android call sites resolve learning
 * languages through [all] and [get]; `LanguageRegistry` keeps the published cross-platform contract.
 *
 * shortcut: hosted (Mural Minutes) voice rejects a locale `services/api/src/live-provider.ts` does
 * not list; add pt-PT there and deploy before offering Portugal voice on that provider.
 */
object AndroidLanguages {
    val portuguesePortugal = LanguageModule(
        id = "pt-PT",
        name = "Portuguese",
        nativeName = "Português",
        variety = "Portugal",
        locale = "pt-PT",
        greeting = "Olá!",
        greetingWord = "olá",
        speechGuidance = "Use clear, natural European Portuguese as spoken in Portugal, with reduced unstressed vowels, syllable-final s and z pronounced as a sh sound, as in os amigos and estava, and enclitic pronoun placement such as diz-me, dá-me and vamos conversar. Use tu with friends and family, você or the third person without a pronoun in polite address, and o senhor or a senhora when formality fits; follow the learner's choice and accept all three as natural Portugal address. Accept valid regional Portuguese accents and wording, including Brazilian, African and island varieties, without marking them as errors. Do not imitate a regional caricature or infer a pronunciation error from a transcript alone.",
        writingGuidance = "Use standard contemporary European Portuguese spelling and accents, including á, é, í, ó, ú, â, ê, ô, ã, õ and ç. Prefer everyday Portugal wording such as autocarro, comboio, pequeno-almoço, telemóvel and casa de banho, and the progressive a + infinitivo, estou a fazer, rather than the Brazilian gerund. Keep object pronouns after the verb in affirmative sentences, diz-me and vejo-te, and before it where European usage requires, não me digas. Accept valid Brazilian spelling, vocabulary and gerund forms from the learner without calling them errors.",
        lemmaGuidance = "Give nouns with their singular article and verbs in the infinitive, for example o comboio, a chávena, o telemóvel and ir. Preserve accents, nasal vowels and ç. Keep hyphenated clitic forms readable: quote diz-me as the observed form and give dizer as the lemma. Keep reflexive and pronominal verbs such as lembrar-se and esquecer-se distinct. Use a consistent European dictionary form without treating Brazilian alternatives as errors.",
        teachingFocus = listOf(
            "Greetings, introductions and useful everyday chunks such as olá, bom dia, faz favor and com licença.",
            "Everyday questions, gender and number agreement, the present tense, ser and estar, and natural tu or você address.",
            "Connected stories, pretérito perfeito and imperfeito in context, plans and familiar situations, with pronouns after the verb such as disse-me.",
            "Reasons and opinions, the a + infinitivo progressive, object pronoun placement, polite requests and common subjunctive contexts.",
            "Nuance, future subjunctive, personal infinitive, hypothetical situations, idiomatic European phrasing and register.",
            "Flexible advanced discussion with precise, natural European Portuguese and appropriate tone."),
        topicPlaceholder = "Comida, música, viagens, a vida em Portugal…",
        lookupUnavailableReply = "Não consegui verificar isso agora. Se quiseres, podemos falar sobre o assunto de forma geral.",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "Uma bica?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood pastelaria in Portugal. Order a bica and a pastel de nata, then chat about the learner's day with natural Portugal address.", 0),
            "groceries" to ConversationTheme("groceries", "No mercado", "A little of everything", "basket", "Everyday", "Shop at a Portuguese market or a small mercearia. Practise quantities, prices and polite requests such as faz favor, using European product names.", 2),
            "travel" to ConversationTheme("travel", "De comboio ou de elétrico", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Portugal by CP comboio, metro or a Lisbon elétrico. Discuss routes and tickets without inventing current schedules or fares.", 1),
            "walk" to ConversationTheme("walk", "Um passeio", "Out into the fresh air", "tree", "Local life", "Take an imagined walk in Portugal, up to a miradouro or along the riverfront. Talk about the view, the weather and everyday life.", 2),
            "cabin" to ConversationTheme("cabin", "Um fim de semana fora", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in Portugal: the Alentejo, the Douro valley or the coast. Discuss travel, food, walks and relaxing together.", 2),
            "restaurant" to ConversationTheme("restaurant", "Jantar numa tasca", "Stay for dessert", "wineglass", "Everyday", "Role-play dinner in a Portuguese tasca or restaurante. Practise requests, the menu and polite problem-solving with natural European address.", 0),
            "traditions" to ConversationTheme("traditions", "Santos Populares", "Small customs, big stories", "flag", "Local life", "Talk about Portuguese traditions such as the Santos Populares in June, sardines, fado or a family meal. Compare individual customs without treating Portugal or Portuguese-speaking cultures as uniform.", 2))
    )
    val extraModules: List<LanguageModule> = listOf(portuguesePortugal)
    val all: List<LanguageModule> = LanguageRegistry.all + extraModules
    fun get(id: String): LanguageModule? = all.firstOrNull { it.id == id }
}
