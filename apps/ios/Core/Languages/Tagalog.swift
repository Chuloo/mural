import Foundation

extension LanguageModule {
    public static let tagalog = LanguageModule(
        id: "tl", name: "Tagalog (Filipino)", nativeName: "Tagalog (Filipino)", variety: "Philippines", locale: "tl-PH",
        greeting: "Kumusta!", greetingWord: "kumusta",
        speechGuidance: "Use clear, natural everyday Tagalog as spoken in the Philippines. Filipino is a learner-facing name for this teaching option; do not switch to another Philippine language. Use po and opo, and polite kayo, when the relationship and situation call for respect; do not force formal address between friends. Accept valid regional Tagalog and established loanwords without treating them as errors. Welcome English or mixed learner replies as support and bridge back to a useful Tagalog phrase, while keeping your own speech in Tagalog. Do not imitate a regional caricature or infer stress, glottal-stop or other pronunciation errors from a transcript alone.",
        writingGuidance: "Use contemporary Latin-script Tagalog with natural conversational wording. Use ordinary spelling in replies; preserve any learner-supplied diacritics, apostrophes and hyphens in quotations. Keep meaningful distinctions such as ng and nang, linkers na and -ng, and reduplicated forms such as araw-araw. Accept common conversational contractions without automatically calling them wrong. Do not append English translations, pronunciation guides or Baybayin to ordinary replies.",
        lemmaGuidance: "Use a consistent Tagalog dictionary citation form: nouns without ang, ng, sa or a plural marker, and verbs in their neutral/infinitive form with the voice/focus affix retained. For example, group kumakain and kakain under kumain, but kinain and kinakain under the separate lemma kainin; do not reduce both to the root kain. Keep useful chunks intact and preserve lexical hyphens. Use ordinary unaccented citation spelling consistently unless a diacritic is needed to distinguish a lexical meaning; keep distinct English senses separate. Preserve the observed form and exact quotation, including any diacritics. In mixed replies, include only identifiable Tagalog words or established loanwords used in Tagalog context; omit ambiguous or English-only evidence rather than labeling it tl.",
        teachingFocus: [
            "Greetings, introductions, thanks and useful requests such as kumusta, salamat and gusto ko ng, with one short exchange at a time.",
            "Everyday questions, pronouns including tayo versus kami, ang/ng/sa markers, basic linkers and polite po/opo in context.",
            "Connected accounts of activities and plans; common completed, ongoing and contemplated verb aspects with time expressions, rather than a direct copy of English tenses.",
            "Reasons and opinions, actor and object voice/focus, consistent participant markers, connected clauses and practical problem-solving.",
            "Hypotheticals, reported information, conversational particles such as na, pa, naman and daw/raw, nuanced requests and changes of register.",
            "Flexible extended discussion with precise, idiomatic Tagalog, natural aspect and voice choices, appropriate tone and respect for regional variation."
        ],
        topicPlaceholder: "Food, travel, music, life in the Philippines…",
        lookupUnavailableReply: "Hindi ko ito masuri ngayon. Kung gusto mo, puwede nating pag-usapan ang paksa sa pangkalahatan.",
        themeOverrides: [
            "coffee": .init("coffee", "Kape tayo?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood café in the Philippines. Order a drink and chat about the learner's day. Use natural Tagalog and polite requests appropriate to the situation.", 0),
            "groceries": .init("groceries", "Sa palengke", "A little of everything", "basket", "Everyday", "Shop at a local market in the Philippines. Practise quantities, prices and friendly requests. Respect regional food names and ask about preferences.", 2),
            "travel": .init("travel", "Saan tayo pupunta?", "Find your way", "tram", "Everyday", "Plan a trip in the Philippines. Choose suitable transport together and practise directions, destinations and tickets without inventing current routes, fares or schedules.", 1),
            "cabin": .init("cabin", "Isang maikling bakasyon", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in the Philippines. Let the learner choose a city, coast or countryside, then discuss travel, food and activities.", 2),
            "traditions": .init("traditions", "Kuwentuhan sa hapag", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal in the Philippines about routines, family and everyday customs. Compare individual experiences without assuming one religion, language or tradition represents everyone.", 2)
        ]
    )
}
