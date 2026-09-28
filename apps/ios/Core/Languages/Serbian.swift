import Foundation

extension LanguageModule {
    public static let serbian = LanguageModule(
        id: "sr", name: "Serbian", nativeName: "Srpski", variety: "Serbia", locale: "sr-Latn-RS",
        greeting: "Zdravo!", greetingWord: "zdravo",
        speechGuidance: "Use clear, natural Standard Serbian pronunciation as spoken in Serbia, with ekavian forms such as mleko, lepo and dete. Use ti for friendly conversation and Vi when the situation calls for formality. Model word stress, vowel length, syllabic r and the sounds č, ć, dž, đ, š and ž naturally. Accept valid regional accents, ijekavian forms such as mlijeko and dijete, and regional vocabulary without treating them or a non-native accent alone as an error. Do not infer a pronunciation error from spelling alone, including č and ć or dž and đ written the wrong way in a transcript.",
        writingGuidance: "Write Serbian only in the Latin script, never Cyrillic, with the standard letters č, ć, dž, đ, š and ž and ekavian spelling. Accept learner text in Cyrillic, in ijekavian spelling or typed without diacritics as valid input; you may model the standard Latin ekavian form, but never treat the script, ijekavian spelling or missing diacritics in a casual typed reply as a language error. Use standard punctuation and match the register to the situation.",
        lemmaGuidance: "Give nouns in the nominative singular, adjectives in the masculine nominative singular and verbs in the infinitive, in the Latin script with ekavian spelling, for example kuća, lep and pisati. Keep the exact observed form and quote unchanged, including Cyrillic, ijekavian or undiacritized input. Keep aspect pairs such as pisati and napisati distinct, keep se with reflexive verbs such as smejati se, and preserve meaningful chunks such as hvala lepo and nema problema.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as zovem se, drago mi je and može jedna kafa.",
            "Everyday questions, noun gender, the present tense, nominative and accusative cases and common prepositions.",
            "Connected stories, the past tense and future plans, genitive, dative and locative cases in familiar situations, and verb aspect in context.",
            "Reasons and opinions, instrumental and vocative cases, clitic word order, conditional requests with bih and precise aspect choice.",
            "Nuance, hypothetical situations, idiomatic phrasing, colloquial and formal register and regional variation.",
            "Flexible advanced discussion with precise, natural Serbian and appropriate tone."
        ],
        topicPlaceholder: "Food, music, travel, life in Serbia…",
        lookupUnavailableReply: "Trenutno ne mogu to da proverim. Ako želiš, možemo da pričamo o toj temi uopšteno.",
        themeOverrides: [
            "coffee": .init("coffee", "Idemo na kafu?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet a friend at a café in Serbia for a coffee. Order a drink, greet the staff politely and chat about the learner's day.", 0),
            "groceries": .init("groceries", "Na pijaci", "A little of everything", "basket", "Everyday", "Visit an open-air green market in Serbia. Practise quantities, prices and polite requests, then ask what the learner likes to cook.", 2),
            "travel": .init("travel", "Na putu", "A ticket to somewhere", "tram", "Everyday", "Plan a trip around Serbia by bus or train. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "Vikend van grada", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in Serbia. Choose a city, the mountains or the countryside together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Slava", "A family feast day", "flame", "Local life", "Talk about slava, the family saint's day many Serbian families celebrate, and other everyday customs. Ask about the learner's own traditions and do not treat everyone in Serbia as alike.", 2)
        ]
    )
}
