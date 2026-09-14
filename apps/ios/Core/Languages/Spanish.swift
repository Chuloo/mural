import Foundation

extension LanguageModule {
    public static let spanish = LanguageModule(
        id: "es", name: "Spanish", nativeName: "Español", variety: "Spain", locale: "es-ES",
        greeting: "¡Hola!", greetingWord: "hola",
        speechGuidance: "Use clear Spanish from Spain, with a natural distinction between s and z/soft c, tú for friendly singular address and vosotros for informal plural address. Accept seseo, ustedes, voseo and other valid regional forms without marking them wrong. Do not imitate a regional caricature.",
        writingGuidance: "Use standard Spanish spelling, accents and opening question and exclamation marks.",
        lemmaGuidance: "Give nouns with their singular grammatical article and verbs in the infinitive, for example la casa and hablar. Keep reflexive verbs such as llamarse distinct. Preserve accents and ñ.",
        teachingFocus: [
            "Greetings, introductions and short useful chunks such as me llamo and quiero.",
            "Everyday questions, gender and number agreement, present tense and useful ser/estar contrasts.",
            "Connected stories, past events, object pronouns and familiar situations.",
            "Reasons and opinions, contrasts between past tenses and common subjunctive contexts.",
            "Nuance, hypothetical situations, register and regional variation.",
            "Flexible advanced discussion with precise, idiomatic Spanish."
        ],
        topicPlaceholder: "Food, travel, music, life in Spain…",
        lookupUnavailableReply: "No he podido comprobarlo ahora mismo. Si quieres, podemos hablar del tema en general.",
        themeOverrides: [
            "coffee": .init("coffee", "Un café", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood café in Spain. Order a drink and chat. Ask about the learner's interests.", 0),
            "groceries": .init("groceries", "En el mercado", "A little of everything", "basket", "Everyday", "Visit a local market in a Spanish-speaking community. Practise quantities, prices and polite questions. Respect regional food vocabulary.", 2),
            "travel": .init("travel", "Next stop", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Spain. Discuss transport and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "Somewhere in the sunshine", "mountain.2", "Local life", "Plan an imagined weekend in a Spanish-speaking place. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "La sobremesa", "Let the conversation linger", "fork.knife", "Local life", "Talk after a shared meal about daily routines, family and local customs. Compare experiences without treating Spanish-speaking cultures as uniform.", 2)
        ]
    )
}
