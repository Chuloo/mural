import Foundation

extension LanguageModule {
    public static let greek = LanguageModule(
        id: "el", name: "Greek", nativeName: "Ελληνικά", variety: "Greece", locale: "el-GR",
        greeting: "Γεια σου!", greetingWord: "γεια",
        speechGuidance: "Use clear, natural Modern Standard Greek as spoken in Greece, with contemporary pronunciation, lexical stress and conversational intonation. Greek has lexical stress rather than lexical tones; do not model Ancient Greek pitch accents or reconstructed pronunciation. Model vowel digraphs, αυ/ευ voicing, and the modern sounds of β, γ, δ and θ naturally. Use friendly singular εσύ and polite plural εσείς as the situation requires. Accept valid regional and Cypriot Greek usage and a non-native accent without treating them alone as errors. Never infer an incorrect stress, sound or intonation from a possibly mistranscribed word; ask when uncertain.",
        writingGuidance: "Write contemporary Greek in the Greek alphabet with monotonic spelling. Preserve meaningful tonos and diaeresis, including ΐ and ΰ, and final sigma ς. Use the Greek question mark ; naturally. Accept equivalent composed and decomposed Unicode text, customary accent omission in all-capital text, and valid regional wording. Welcome Greeklish or another language as learner support and model the Greek form without calling the script choice a grammar error. Keep exact learner quotations unchanged. Do not add transliteration or English translations to ordinary replies.",
        lemmaGuidance: "Give nouns in nominative singular without an article, adjectives in masculine nominative singular and verbs in the dictionary first-person singular present, for example καφές, καλός and γράφω. Modern Greek has no productive infinitive; do not invent one or use an English-style bare stem. Retain voice and lexical meaning, so γράφω and γράφομαι remain distinct. Group inflected forms such as καφέ under καφές and έγραψα under γράφω when that sense is supported. Preserve tonos and diaeresis in citation forms; do not conflate πότε with ποτέ or που with πού. Keep useful chunks intact and preserve observed forms and exact quotations, including elision, final sigma and decomposed characters. Omit ambiguous Greeklish vocabulary rather than claiming independent Greek production.",
        teachingFocus: [
            "Greetings, introductions and useful requests such as γεια σου, ευχαριστώ and θα ήθελα, in short exchanges.",
            "Everyday questions, gender and articles, present forms, nominative and accusative cases, and polite singular or plural address.",
            "Connected accounts of past events and plans; imperfective and perfective aspect, common past forms, θα and να clauses, and genitive in context.",
            "Reasons and opinions, pronoun clitics, agreement, δεν versus μην, requests and practical problem-solving with appropriate aspect.",
            "Hypotheticals, reported information, nuanced connectors, idiomatic phrasing, register and regional alternatives.",
            "Flexible extended discussion in precise, natural Modern Greek with appropriate stress, aspect, voice and tone."
        ],
        topicPlaceholder: "Food, music, travel, life in Greece…",
        lookupUnavailableReply: "Δεν μπορώ να το ελέγξω τώρα. Αν θέλεις, μπορούμε να μιλήσουμε γενικά για το θέμα.",
        themeOverrides: [
            "coffee": .init("coffee", "Πάμε για καφέ;", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a café in Greece. Order a coffee, greet the staff and chat about the learner's day with suitable polite address.", 0),
            "groceries": .init("groceries", "Στη λαϊκή", "A little of everything", "basket", "Everyday", "Shop at an imagined street market in Greece. Practise quantities, prices and polite requests, then ask about cooking preferences.", 2),
            "travel": .init("travel", "Πού πάμε;", "Find your way", "tram", "Everyday", "Plan a trip in Greece. Choose transport and practise directions and tickets without inventing current schedules or fares.", 1),
            "cabin": .init("cabin", "Μια μικρή απόδραση", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in Greece. Choose a city, coast or mountain village together and discuss practical plans.", 2),
            "traditions": .init("traditions", "Γύρω από το τραπέζι", "Stay a little longer", "fork.knife", "Local life", "Talk about meals, everyday customs and celebrations in Greece. Ask about individual experiences without assuming one religion or tradition represents everyone.", 2)
        ]
    )
}
