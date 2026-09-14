import Foundation

extension LanguageModule {
    public static let german = LanguageModule(
        id: "de", name: "German", nativeName: "Deutsch", variety: "Germany", locale: "de-DE",
        greeting: "Hallo!", greetingWord: "hallo",
        speechGuidance: "Use clear, natural Standard German as spoken in Germany. Use du for friendly conversation and Sie when the situation calls for formality. Accept valid Austrian, Swiss and other regional pronunciation, vocabulary and grammar. Do not treat a regional difference or a non-native accent alone as an error. Correct pronunciation only when supported by the audio, not a transcript alone.",
        writingGuidance: "Use standard German spelling, noun capitalization, umlauts and ß. Accept Swiss ss spellings and valid regional wording. Match the register to the situation.",
        lemmaGuidance: "Give nouns with their singular article and verbs in the infinitive, for example das Haus, die Straße and sprechen. Preserve umlauts and ß. Keep separable verbs such as aufstehen and reflexive verbs such as sich erinnern together as dictionary entries, while quoting the learner's actual word order exactly.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as ich heiße and ich möchte.",
            "Everyday questions, grammatical gender, present tense, verb-second word order and common accusative objects.",
            "Connected stories, conversational past tenses, dative uses, separable verbs and familiar situations.",
            "Reasons and opinions, subordinate-clause word order, relative clauses and polite Konjunktiv II requests.",
            "Nuance, hypothetical situations, passive voice, idiomatic phrasing and regional register.",
            "Flexible advanced discussion with precise, natural German and appropriate tone."
        ],
        topicPlaceholder: "Food, travel, music, life in Germany…",
        lookupUnavailableReply: "Das konnte ich gerade nicht überprüfen. Wenn du möchtest, können wir allgemein über das Thema sprechen.",
        themeOverrides: [
            "coffee": .init("coffee", "Ein Kaffee?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood café in Germany. Order a drink and chat. Use polite greetings with staff and follow the learner's interests.", 0),
            "groceries": .init("groceries", "Auf dem Markt", "A little of everything", "basket", "Everyday", "Shop at a weekly market in Germany. Practise quantities, prices and polite requests, accepting regional names for foods.", 2),
            "travel": .init("travel", "Unterwegs", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Germany. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in a German-speaking place. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Feierabend", "After the working day", "flag", "Local life", "Talk about routines after work and local customs in Germany. Compare the learner's experiences without treating German-speaking cultures as uniform.", 2)
        ]
    )
}
