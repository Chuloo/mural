import Foundation

extension LanguageModule {
    public static let italian = LanguageModule(
        id: "it", name: "Italian", nativeName: "Italiano", variety: "Italy", locale: "it-IT",
        greeting: "Ciao!", greetingWord: "ciao",
        speechGuidance: "Use clear, natural Standard Italian pronunciation. Use tu for friendly conversation and Lei when the situation calls for formality. Model vowel sounds, word stress and consonant length naturally. Accept valid regional accents and vocabulary without treating regional variation or a non-native accent alone as an error. Do not infer a pronunciation error from spelling alone.",
        writingGuidance: "Use standard Italian spelling, accents, apostrophes and punctuation. Preserve meaningful contrasts such as e and è. Match the register to the situation and accept valid regional usage.",
        lemmaGuidance: "Give nouns with their singular article and verbs in the infinitive, for example la casa, lo studente and parlare. Preserve elisions and accents. Keep reflexive verbs such as chiamarsi and pronominal verbs such as farcela distinct.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as mi chiamo and vorrei.",
            "Everyday questions, gender and number agreement, present tense and common prepositions.",
            "Connected stories, passato prossimo and imperfetto in context, future plans and familiar situations.",
            "Reasons and opinions, object pronouns, conditional requests and common congiuntivo contexts.",
            "Nuance, hypothetical situations, pronoun combinations, idiomatic phrasing and regional register.",
            "Flexible advanced discussion with precise, natural Italian and appropriate tone."
        ],
        topicPlaceholder: "Food, cinema, travel, life in Italy…",
        lookupUnavailableReply: "Non sono riuscito a verificarlo adesso. Se vuoi, possiamo parlare dell'argomento in generale.",
        themeOverrides: [
            "coffee": .init("coffee", "Un caffè?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood bar in Italy for a coffee. Order a drink, greet the staff politely and chat about the learner's day.", 0),
            "groceries": .init("groceries", "Al mercato", "A little of everything", "basket", "Everyday", "Visit a local market in Italy. Practise quantities, prices and polite requests, then ask what the learner likes to cook.", 2),
            "travel": .init("travel", "In viaggio", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Italy. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in Italy. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "La passeggiata", "An evening walk", "figure.walk", "Local life", "Take an imagined evening walk and discuss daily routines and local customs. Compare experiences without treating Italian communities as uniform.", 2)
        ]
    )
}
