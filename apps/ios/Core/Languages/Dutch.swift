import Foundation

extension LanguageModule {
    public static let dutch = LanguageModule(
        id: "nl", name: "Dutch", nativeName: "Nederlands", variety: "Netherlands", locale: "nl-NL",
        greeting: "Hoi!", greetingWord: "hoi",
        speechGuidance: "Use clear, natural Standard Dutch as spoken in the Netherlands, with conversational rhythm, vowel length, the ui and ij/ei diphthongs, and natural g/ch sounds. Accept valid Belgian Dutch and regional pronunciation without treating an accent difference as an error. Use je and jij with friends and u when polite address fits the situation. Never infer a pronunciation error from a transcript alone.",
        writingGuidance: "Use contemporary Dutch spelling, preserving diacritics and meaningful compounds. Keep separable verb particles and word order natural. Accept valid regional wording, composed and decomposed Unicode, and learner support in another language. Keep exact quotations unchanged. Do not append English translations or pronunciation guides to ordinary replies.",
        lemmaGuidance: "Give nouns in singular with their dictionary article de or het and verbs in the infinitive, for example de fiets, het huis and fietsen. Group inflected and separated forms such as fietste under fietsen and belt op under opbellen when the sense is supported. Preserve reflexive verbs and useful phrases. Keep observed forms and exact quotations unchanged; omit ambiguous foreign-language evidence.",
        teachingFocus: [
            "Greetings, introductions and short useful requests such as hoi, dank je and ik wil graag.",
            "Everyday questions, de and het, present tense, adjective agreement and basic word order.",
            "Connected stories, past events, perfect tense, separable verbs and everyday plans.",
            "Reasons and opinions, subordinate word order, er, modal verbs and practical problem-solving.",
            "Hypotheticals, idiomatic phrasing, register and nuanced connectors.",
            "Flexible extended discussion with precise, natural Dutch and appropriate tone."
        ],
        topicPlaceholder: "Food, cycling, travel, everyday life…",
        lookupUnavailableReply: "Ik kan dat nu niet controleren. Als je wilt, kunnen we in het algemeen over het onderwerp praten.",
        themeOverrides: [
            "coffee": .init("coffee", "Een koffie?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a café in the Netherlands. Order a drink and chat with suitable polite address.", 0),
            "groceries": .init("groceries", "Op de markt", "A little of everything", "basket", "Everyday", "Shop at an imagined market in the Netherlands. Practise quantities, prices and friendly requests.", 2),
            "travel": .init("travel", "Waar gaan we heen?", "Find your way", "tram", "Everyday", "Plan an imagined trip in the Netherlands. Practise directions and tickets without inventing current schedules or fares.", 1),
            "cabin": .init("cabin", "Een weekend weg", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in the Netherlands. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Aan tafel", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal in the Netherlands. Compare individual customs without assuming one religion or tradition represents everyone.", 2)
        ]
    )
}
