import Foundation

extension LanguageModule {
    public static let russian = LanguageModule(
        id: "ru", name: "Russian", nativeName: "Русский", variety: "Standard", locale: "ru-RU",
        greeting: "Привет!", greetingWord: "привет",
        speechGuidance: "Use clear, natural Standard Russian with conversational intonation, lexical stress, unstressed vowel reduction and appropriate hard and soft consonants. Use ты with friends and вы when polite address fits the situation. Accept valid regional pronunciation without treating an accent difference as an error. Never infer a stress or pronunciation error from a transcript alone.",
        writingGuidance: "Write contemporary Russian in Cyrillic with natural punctuation. Preserve ё where it clarifies the word and preserve learner-supplied ё and stress marks in quotations. Accept customary е for ё, composed and decomposed Unicode, and transliteration as learner support; model the Cyrillic form without calling the script choice a grammar error. Do not append transliteration, stress marks or English translations to ordinary replies.",
        lemmaGuidance: "Give nouns in nominative singular, adjectives in masculine nominative singular and verbs in the infinitive, for example кофе, хороший and говорить. Keep perfective and imperfective verbs such as сделать and делать distinct, and retain reflexive endings. Group case-inflected forms such as книгу under книга when supported. Preserve meaningful ё, useful phrases, observed forms and exact quotations. Omit ambiguous transliterated or foreign-language evidence rather than claiming independent Russian production.",
        teachingFocus: [
            "Greetings, introductions and short useful requests such as привет, спасибо and можно.",
            "Everyday questions, gender, present forms, basic case uses and polite ты or вы in context.",
            "Connected stories, past events and plans; case agreement and common verb aspect contrasts.",
            "Reasons and opinions, verbs of motion, aspect, connected clauses and practical problem-solving.",
            "Hypotheticals, participial constructions, idiomatic phrasing and changes of register.",
            "Flexible extended discussion with precise, natural Russian and appropriate aspect, stress and tone."
        ],
        topicPlaceholder: "Food, music, travel, everyday life…",
        lookupUnavailableReply: "Сейчас я не могу это проверить. Если хочешь, мы можем поговорить об этой теме в целом.",
        themeOverrides: [
            "coffee": .init("coffee", "Выпьем кофе?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a café in a Russian-speaking setting. Order a drink and chat with suitable polite address.", 0),
            "groceries": .init("groceries", "На рынке", "A little of everything", "basket", "Everyday", "Shop at an imagined market in a Russian-speaking setting. Practise quantities, prices and friendly requests.", 2),
            "travel": .init("travel", "Куда поедем?", "Find your way", "tram", "Everyday", "Plan an imagined trip in a Russian-speaking setting. Practise directions and tickets without inventing current schedules or fares.", 1),
            "cabin": .init("cabin", "На выходные", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in a Russian-speaking setting. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "За столом", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal in a Russian-speaking setting. Compare individual customs without assuming one religion or tradition represents everyone.", 2)
        ]
    )
}
