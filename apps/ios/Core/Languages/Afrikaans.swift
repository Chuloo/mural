import Foundation

extension LanguageModule {
    public static let afrikaans = LanguageModule(
        id: "af", name: "Afrikaans", nativeName: "Afrikaans", variety: "South Africa", locale: "af-ZA",
        greeting: "Hallo!", greetingWord: "hallo",
        speechGuidance: "Use clear, natural Standard Afrikaans as spoken in South Africa. Use jy and jou in friendly conversation and u when the situation calls for formality. Accept valid Cape, Namibian and other regional pronunciation, vocabulary and grammar, including common English loanwords and everyday code-switching. Do not drift into Dutch. Do not treat a regional difference or a non-native accent alone as an error. Correct pronunciation only when supported by the audio, not a transcript alone.",
        writingGuidance: "Use standard Afrikaans spelling, including the apostrophe in 'n, the diacritics ê, ë, ô and ü, and the double negative nie … nie. Do not use Dutch spellings such as ij or z where Afrikaans uses y or s. Accept valid regional wording. Match the register to the situation.",
        lemmaGuidance: "Give nouns in the singular without an article, for example huis, straat and koffie, and verbs in the base form, for example praat and eet. Preserve the apostrophe in 'n and diacritics. Keep separable verbs such as opstaan and reflexive verbs such as jou verbeel together as dictionary entries, while quoting the learner's actual word order exactly.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as ek is, ek wil graag and baie dankie.",
            "Everyday questions, the present tense without conjugation, the double negative nie … nie, plurals and common verbs such as het and is.",
            "Connected stories, the past tense with het and ge-, verb-second word order in longer sentences, separable verbs and familiar situations.",
            "Reasons and opinions, subordinate clauses with dat, omdat and wat, modal verbs, comparatives and polite requests.",
            "Nuance, hypothetical situations with sou, the passive with word and is, idiomatic phrasing and regional register.",
            "Flexible advanced discussion with precise, natural Afrikaans and appropriate tone."
        ],
        topicPlaceholder: "Food, travel, music, life in South Africa…",
        lookupUnavailableReply: "Ek kon dit nie nou nagaan nie. As jy wil, kan ons in die algemeen oor die onderwerp gesels.",
        themeOverrides: [
            "coffee": .init("coffee", "'n Koffie?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood coffee shop in South Africa. Order a drink and chat. Use polite greetings with staff and follow the learner's interests.", 0),
            "groceries": .init("groceries", "By die mark", "A little of everything", "basket", "Everyday", "Shop at a farmers' market or corner shop in South Africa. Practise quantities, prices in rand and polite requests, accepting regional names for foods.", 2),
            "travel": .init("travel", "Op pad", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in South Africa by road, rail or air. Discuss directions, tickets and stops without inventing current schedules.", 1),
            "cabin": .init("cabin", "'n Naweek weg", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in South Africa. Choose the coast, the Karoo, the mountains or the bushveld together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Om die braai", "Around the fire", "flag", "Local life", "Talk about a braai, sport and everyday customs in South Africa. Compare the learner's experiences without treating Afrikaans-speaking communities as uniform.", 2)
        ],
        // NaturalLanguage has no Afrikaans class and reports Afrikaans transcripts as Dutch.
        detectorAliases: ["nl"]
    )
}
