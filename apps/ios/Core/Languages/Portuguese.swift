import Foundation

extension LanguageModule {
    public static let portuguese = LanguageModule(
        id: "pt", name: "Portuguese", nativeName: "Português", variety: "Brazil", locale: "pt-BR",
        greeting: "Olá!", greetingWord: "olá",
        speechGuidance: "Use clear, natural Brazilian Portuguese with broadly intelligible pronunciation and consistent Brazilian vocabulary. Use você in friendly conversation and formal address when appropriate. Accept valid uses of tu, regional Brazilian accents and grammar, and European, African and other Portuguese varieties without marking them wrong. Do not imitate a regional caricature or infer pronunciation errors from a transcript alone.",
        writingGuidance: "Use standard contemporary Brazilian Portuguese spelling, accents, ã, õ and ç. Prefer everyday Brazilian wording, including a gente and conversational pronoun placement when natural. Accept valid regional and European Portuguese usage from the learner.",
        lemmaGuidance: "Give nouns with their singular article and verbs in the infinitive, for example a casa, o pão and falar. Preserve accents, nasal vowels and ç. Keep reflexive and pronominal verbs such as se lembrar distinct. Use a consistent Brazilian dictionary form without treating regional alternatives as errors.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as meu nome é and eu gostaria de.",
            "Everyday questions, gender and number agreement, present tense, ser and estar, and você and a gente.",
            "Connected stories, pretérito perfeito and imperfeito in context, future plans and familiar situations.",
            "Reasons and opinions, object pronouns, polite requests and common subjunctive contexts.",
            "Nuance, future subjunctive, personal infinitive, hypothetical situations, idiomatic phrasing and regional register.",
            "Flexible advanced discussion with precise, natural Brazilian Portuguese and appropriate tone."
        ],
        topicPlaceholder: "Food, music, travel, life in Brazil…",
        lookupUnavailableReply: "Não consegui verificar isso agora. Se quiser, podemos conversar sobre o assunto de forma geral.",
        themeOverrides: [
            "coffee": .init("coffee", "Um cafezinho?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood café or padaria in Brazil. Order a drink and chat about the learner's day, using natural Brazilian vocabulary.", 0),
            "groceries": .init("groceries", "Na feira", "A little of everything", "basket", "Everyday", "Shop at a street market in Brazil. Practise quantities, prices and polite requests, respecting regional food names.", 2),
            "travel": .init("travel", "Pé na estrada", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Brazil. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in Brazil. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Uma conversa à mesa", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal about routines and local customs in Brazil. Compare the learner's experiences without treating Brazilian or Portuguese-speaking cultures as uniform.", 2)
        ]
    )
}
