import Foundation

extension LanguageModule {
    public static let french = LanguageModule(
        id: "fr", name: "French", nativeName: "Français", variety: "France", locale: "fr-FR",
        greeting: "Salut !", greetingWord: "salut",
        speechGuidance: "Use clear, natural metropolitan French pronunciation. Use tu in a friendly conversation and vous when the situation calls for formality or plural address. Accept valid regional accents, vocabulary and grammar from across the French-speaking world. Do not treat regional variation, informal omission of ne or a non-native accent alone as an error. Do not imitate a regional caricature.",
        writingGuidance: "Use standard French spelling, accents, apostrophes and punctuation. Preserve accents on capital letters. Match the register to the situation and accept valid regional usage from the learner.",
        lemmaGuidance: "Give nouns with a singular article that makes gender clear where possible and verbs in the infinitive, for example une maison, un ami and parler. Keep pronominal verbs such as se souvenir distinct. Preserve accents and meaningful elisions.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as je m'appelle and je voudrais.",
            "Everyday questions, grammatical gender, present tense and common negation in conversation.",
            "Connected stories, passé composé and imparfait in context, future plans and familiar situations.",
            "Reasons and opinions, object pronouns, conditional requests and common subjunctive contexts.",
            "Nuance, hypothetical situations, register, idiomatic phrasing and regional variation.",
            "Flexible advanced discussion with precise, natural French and appropriate tone."
        ],
        topicPlaceholder: "Food, cinema, travel, life in France…",
        lookupUnavailableReply: "Je n'ai pas pu vérifier ça pour le moment. On peut parler du sujet en général, si tu veux.",
        themeOverrides: [
            "coffee": .init("coffee", "Un café ?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood café in France. Order a drink and chat. Use polite greetings with staff and a friendly register with the learner.", 0),
            "groceries": .init("groceries", "Au marché", "A little of everything", "basket", "Everyday", "Visit a local market in France. Practise quantities, prices and polite requests, then ask what the learner likes to cook.", 2),
            "travel": .init("travel", "En route", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in France. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in a French-speaking place. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "À table", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal about daily routines and local customs. Compare the learner's experiences with life in France without treating French-speaking cultures as uniform.", 2)
        ]
    )
}
