import Foundation

extension LanguageModule {
    /// Portugal's stable ID is independent of `.portuguese` (Brazil): the two must never
    /// share a storage namespace or reinterpret each other's learner progress.
    public static let portugueseEuropean = LanguageModule(
        id: "pt-pt", name: "Portuguese", nativeName: "Português", variety: "Portugal", locale: "pt-PT",
        greeting: "Olá!", greetingWord: "olá",
        speechGuidance: "Use clear, natural European Portuguese pronunciation, including reduced unstressed vowels and a natural European rhythm. Use tu in friendly conversation and o senhor/a senhora for formal address; avoid você, which can sound stilted or overly formal in Portugal. Accept valid Brazilian, African and other Portuguese varieties from the learner without marking them wrong. Do not imitate a regional caricature or infer pronunciation errors from a transcript alone.",
        writingGuidance: "Use standard European Portuguese spelling under the current orthographic agreement, including forms such as ótimo, receção and deteção that differ from Brazilian spelling. Prefer everyday European wording, such as pequeno-almoço, autocarro, comboio and casa de banho, and progressive forms such as estou a fazer rather than estou fazendo. Accept valid regional and Brazilian Portuguese usage from the learner.",
        lemmaGuidance: "Give nouns with their singular article and verbs in the infinitive, for example a casa, o pão and falar. Preserve accents, nasal vowels and ç. Keep reflexive and pronominal verbs such as chamar-se distinct, using the enclitic placement typical of Portugal. Use a consistent European dictionary form without treating regional alternatives as errors.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as chamo-me and queria.",
            "Everyday questions, gender and number agreement, present tense, ser and estar, and tu versus formal address.",
            "Connected stories, pretérito perfeito and imperfeito in context, future plans and familiar situations.",
            "Reasons and opinions, clitic pronoun placement and agreement, polite requests and common subjunctive contexts.",
            "Nuance, future subjunctive, personal infinitive, mesóclise, hypothetical situations, idiomatic phrasing and regional register.",
            "Flexible advanced discussion with precise, natural European Portuguese and appropriate tone."
        ],
        topicPlaceholder: "Food, football, travel, life in Portugal…",
        lookupUnavailableReply: "Não consegui verificar isso agora. Se quiser, podemos falar sobre o assunto de forma geral.",
        themeOverrides: [
            "coffee": .init("coffee", "Uma bica?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood café or pastelaria in Portugal. Order a drink and chat about the learner's day, using natural European Portuguese vocabulary.", 0),
            "groceries": .init("groceries", "No mercado", "A little of everything", "basket", "Everyday", "Shop at a local market in Portugal. Practise quantities, prices and polite requests, respecting regional food names.", 2),
            "travel": .init("travel", "Próxima paragem", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Portugal. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "A weekend away", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in Portugal. Choose a city, coast or countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Depois do almoço", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal about routines and local customs in Portugal. Compare the learner's experiences without treating Portuguese-speaking cultures as uniform.", 2)
        ]
    )
}
