import Foundation

extension LanguageModule {
    public static let norwegian = LanguageModule(
        id: "nb", name: "Norwegian", nativeName: "Norsk", variety: "Bokmål", locale: "nb-NO",
        greeting: "Hei!", greetingWord: "hei",
        speechGuidance: "Use natural Eastern Norwegian pronunciation. Accept other Norwegian dialects without treating dialect differences as errors.",
        writingGuidance: "Use Norwegian Bokmål spelling and wording.",
        lemmaGuidance: "Give nouns with their singular grammatical article and verbs in the infinitive, for example en tur and å gå. Accept valid gender variants.",
        teachingFocus: [
            "Greetings, introductions and short everyday chunks.",
            "Simple questions, noun gender and present-tense everyday exchanges.",
            "Connected stories, past tense, word order and familiar situations.",
            "Reasons and opinions, subordinate clauses and natural connectors.",
            "Nuanced discussion, idiomatic phrasing and register.",
            "Flexible advanced conversation with precise, natural Norwegian."
        ],
        topicPlaceholder: "Design, space, life in Norway…",
        lookupUnavailableReply: "Jeg klarte ikke å sjekke det akkurat nå. Vi kan snakke om temaet generelt, hvis du vil.",
        themeOverrides: [
            "groceries": .init("groceries", "At the market", "Find the good tomatoes", "basket", "Everyday", "Help the learner shop at a Norwegian food market. Practise quantities and questions.", 2),
            "travel": .init("travel", "Next stop", "A ticket to somewhere", "tram", "Everyday", "Plan a train trip in Norway. Discuss routes and tickets without inventing current schedules.", 1),
            "weather": .init("weather", "Rain again?", "A very Norwegian chat", "cloud.rain", "Local life", "Talk about weather, clothing and outdoor plans in Norway. Verify current forecasts before claiming them.", 1),
            "cabin": .init("cabin", "Cabin weekend", "A quieter kind of day", "mountain.2", "Local life", "Plan a hytte weekend: travel, food, walks and relaxing together.", 2),
            "traditions": .init("traditions", "Life in Norway", "Small customs, big stories", "flag", "Local life", "Explore Norwegian everyday customs with nuance. Avoid treating all Norwegians as alike.", 2)
        ]
    )
}
