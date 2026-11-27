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
 *
 * A Typed-only Module has no voice on any provider, so it needs neither: its whole conversation is
 * typed. Add its id to [typedOnlyIDs]; nothing else in the app decides this per module.
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
    val romanKonkani = LanguageModule(
        id = "kok-Latn",
        name = "Konkani",
        nativeName = "Konknni",
        variety = "Roman script",
        locale = "kok-Latn",
        greeting = "Dev borem korum!",
        greetingWord = "dev borem korum",
        speechGuidance = "Konkani has no speech recognition and no text-to-speech voice on any provider, cloud or on the phone, so this conversation is typed only: never claim to have heard audio, never judge pronunciation, and never ask the learner to say something aloud or repeat it. Judge only what is written. Keep the rhythm and phrasing of natural spoken Romi Konknni as it is written in Goa and Mangalore: short warm sentences, everyday connectors such as ani, punn, zalear and mhunnon, and the direct question style of a tiatr scene. Treat the learner's own regional Romi Konknni and their everyday borrowings from English, Hindi and Marathi as valid Konkani, never as errors.",
        writingGuidance = "Write Konkani in the Roman script only, the way Romi Konknni is written in Goa and Mangalore; never write Devanagari or Kannada. Follow everyday Romi Konknni spelling, for example hanv, tujea, amchem, mhaka, kiteak, khoim, kedna, jevon, bazar, udk, pavs, zatra and Dev borem korum. Accept natural orthographic variation across Goan and Mangalorean Romi Konknni, such as mhaka or maka for 'to me' and the vowel spellings that differ between the two coasts, without calling either form wrong. Accept the everyday code-switching and borrowings from English, Hindi and Marathi that real Konkani speakers write — bus, time, office, school, dukaan, kam — as valid Konkani, never as learner mistakes. Correct only a form that is wrong in the learner's own variety.",
        lemmaGuidance = "Give each word its Roman-script Konkani base form: verbs in the infinitive such as korunk, vochunk and khawunk, nouns in the singular such as kafe, bazar, jevon and udk. Never return a Devanagari or Kannada lemma for a Roman-script quote, and keep the learner's own Roman spelling in quote and form. Treat everyday borrowings from English, Hindi and Marathi written inside a Konkani sentence — bus, time, dukaan, school, kam — as valid Konkani vocabulary: tag them kok-Latn instead of dropping them or labelling them as another language, never as errors. Use en, mixed or uncertain only when the learner clearly switched language for a whole clause. Keep one consistent Roman dictionary form and never treat a Goan or Mangalorean spelling variant as an error.",
        teachingFocus = listOf(
            "Greetings and short everyday chunks such as Dev borem korum, Koxem asa?, Borem asa and Mhojem nanv.",
            "Simple typed questions and answers, present forms, gender and number, such as ek kafe, mhaka zai and tujea ghorant.",
            "Connected short turns about jevon, bazar and ghor, past forms and everyday connectors such as ani, punn and mhunnon.",
            "Reasons, opinions and polite requests, kiteak and zalear clauses, and natural code-switching with English, Hindi and Marathi words.",
            "Nuance, register and idiomatic Romi Konknni, including tiatr, zatra and pavsa references, in longer written turns.",
            "Flexible advanced written discussion with precise, natural Romi Konknni and appropriate tone."),
        topicPlaceholder = "Kafe, jevon, bazar, pavs, zatra, ghor…",
        lookupUnavailableReply = "Maka hem attam polleunk zalem na. Zai zalear ami hea vixoyar sadheponnan uloia.",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "Kafe vo chá?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a Goan kafe or a roadside stall. Order a chá or a kafe and a fresh pao, then chat about the learner's day in natural Romi Konknni, using the address they choose.", 0),
            "groceries" to ConversationTheme("groceries", "Bazarant", "A little of everything", "basket", "Everyday", "Shop at a Konkani bazar or a small dukan. Practise quantities, prices and polite requests, using Konkani names for fish, rice, coconut and vegetables.", 2),
            "travel" to ConversationTheme("travel", "Bos vo feri", "A ticket to somewhere", "tram", "Everyday", "Plan a trip by a local bos or the Mandovi feri. Discuss routes and tickets without inventing current schedules or fares.", 1),
            "dinner" to ConversationTheme("dinner", "Ghorchem jevon", "Let’s make something", "fork.knife", "Everyday", "Plan a family jevon at home. Ask about the ingredients, who cooks what and the steps, from rice and fish curry to sanna and neureo.", 3),
            "walk" to ConversationTheme("walk", "Doriya lagim", "Out into the fresh air", "tree", "Local life", "Take an imagined walk along a Goan or Mangalorean shore, or through the lanes behind the church or the temple. Talk about the sea, the weather and everyday life.", 2),
            "weather" to ConversationTheme("weather", "Pavs", "Whatever the weather", "cloud.rain", "Local life", "Talk about the monsoon pavs, the heat before it and what it changes at home and at work. Verify a forecast before claiming today's weather.", 1),
            "traditions" to ConversationTheme("traditions", "Zatra ani Fest", "Small customs, big stories", "flag", "Local life", "Talk about Konkani traditions such as a zatra, a fest, Konsachem Fest, Shigmo, tiatr or a family jevon. Compare individual customs without treating Goan, Mangalorean or East Indian Konkani communities as uniform.", 2))
    )
    val extraModules: List<LanguageModule> = listOf(portuguesePortugal, romanKonkani)
    /** Language Modules with no voice anywhere: both sides of the conversation are typed. */
    val typedOnlyIDs: Set<String> = setOf("kok-Latn")
    val all: List<LanguageModule> = LanguageRegistry.all + extraModules
    fun get(id: String): LanguageModule? = all.firstOrNull { it.id == id }
}

val LanguageModule.isTypedOnly: Boolean get() = id in AndroidLanguages.typedOnlyIDs
