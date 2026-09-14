import Foundation

extension LanguageModule {
    public static let english = LanguageModule(
        id: "en", name: "English", nativeName: "English", variety: "International", locale: "en",
        greeting: "Hi!", greetingWord: "hi",
        speechGuidance: "Use clear, broadly intelligible English with a consistent, natural pronunciation. Accept valid regional accents, vocabulary and grammar, including British and American forms. Do not treat an accent difference as an error or require imitation of a native accent. Correct pronunciation only when meaning is unclear and the audio supports the correction.",
        writingGuidance: "Use standard English spelling and punctuation. Keep one spelling convention within your own reply, but accept valid regional spelling and usage from the learner.",
        lemmaGuidance: "Give countable nouns in the singular and verbs in the base form, for example a journey and go. Keep meaningful phrasal verbs such as look after together. Use a short, plain English definition as the stable sense rather than repeating the word itself.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as I'd like and my name is.",
            "Everyday questions, present forms, articles and common countable and uncountable nouns.",
            "Connected stories, past events, future plans and familiar situations.",
            "Reasons and opinions, present perfect in context, conditionals and natural linking phrases.",
            "Nuance, idiomatic expressions, reported speech and appropriate register.",
            "Flexible advanced discussion with precise language, implication and tact."
        ],
        topicPlaceholder: "Travel, films, work, everyday life…",
        lookupUnavailableReply: "I couldn't check that just now. We can talk about the topic more generally, if you like.",
        themeOverrides: [
            "coffee": .init("coffee", "A coffee?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood café. Order a drink and chat in English. Follow the learner's interests and accept regional vocabulary.", 0),
            "travel": .init("travel", "Next stop", "A ticket to somewhere", "tram", "Everyday", "Plan a trip using English. Let the learner choose the destination. Discuss transport and tickets without inventing current schedules.", 1),
            "traditions": .init("traditions", "Everyday customs", "Small customs, big stories", "flag", "Local life", "Compare everyday customs from places the learner knows. English is used across many cultures; avoid presenting one country's habits as universal.", 2)
        ]
    )
}
