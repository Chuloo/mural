import Foundation

extension LanguageModule {
    public static let japanese = LanguageModule(
        id: "ja", name: "Japanese", nativeName: "日本語", variety: "Standard", locale: "ja-JP",
        greeting: "こんにちは！", greetingWord: "こんにちは",
        speechGuidance: "Use clear, natural Standard Japanese as spoken in Tokyo, with natural pitch accent, vowel length, geminate consonants and conversational intonation. Use the polite です/ます style by default and plain forms when the learner chooses a casual register or the situation is clearly between friends. Use honorific and humble forms only where the situation calls for them. Accept valid regional pronunciation and wording without treating an accent difference as an error. Never infer a pitch-accent, vowel-length or pronunciation error from a transcript alone.",
        writingGuidance: "Write contemporary Japanese in the customary mix of kanji, hiragana and katakana with Japanese punctuation and no spaces between words. Prefer common kanji and write rarer words in kana. Accept learner text in kana only, in rōmaji or with nonstandard kanji choices as valid input; model the standard written form without calling the script choice a grammar error. Keep exact learner quotations unchanged. The app displays readings separately; do not append furigana, rōmaji or English translations to ordinary replies.",
        lemmaGuidance: "Give vocabulary lemmas in their usual written form with no reading, rōmaji or English in the lemma; the app supplies reading help separately. Give verbs and い-adjectives in the plain dictionary form and な-adjectives without な, for example 食べる, 高い and 静か. Group polite and inflected forms such as 食べました and 食べて under 食べる when supported. Keep transitive and intransitive pairs such as 開ける and 開く distinct, give nouns without particles, and preserve useful chunks such as お願いします. Keep the exact observed form and quotation, including kana-only or rōmaji input. Omit ambiguous rōmaji or foreign-language evidence rather than claiming independent Japanese production. Do not infer pitch accent, pronunciation or spoken recall from a transcript alone.",
        teachingFocus: [
            "Greetings, introductions and short useful requests such as こんにちは, ありがとう and お願いします.",
            "Everyday questions, です and ます forms, the particles は, が, を, に and で, numbers and common counters.",
            "Connected stories, past events and plans; the て-form, adjective inflection and familiar situations.",
            "Reasons and opinions, plain forms, noun-modifying clauses, giving and receiving verbs and practical problem-solving.",
            "Hypotheticals and conditionals, passive and causative forms, idiomatic phrasing, and polite and casual register.",
            "Flexible extended discussion with precise, natural Japanese and appropriate honorific language and tone."
        ],
        topicPlaceholder: "Food, music, travel, everyday life…",
        lookupUnavailableReply: "今はそれを確認できません。よかったら、その話題について一般的に話しましょう。",
        themeOverrides: [
            "coffee": .init("coffee", "コーヒーでもどう？", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a café in Japan. Order a drink and chat, using polite requests with staff and a friendly register with the learner.", 0),
            "groceries": .init("groceries", "買い物に行こう", "A little of everything", "basket", "Everyday", "Shop at an imagined shopping street or supermarket in Japan. Practise quantities, counters, prices and polite requests.", 2),
            "travel": .init("travel", "どこに行く？", "Find your way", "tram", "Everyday", "Plan an imagined trip in Japan. Practise directions and tickets without inventing current schedules or fares.", 1),
            "cabin": .init("cabin", "週末のお出かけ", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend in Japan. Choose a city, coast or hot-spring town together and discuss practical plans.", 2),
            "traditions": .init("traditions", "食卓を囲んで", "Stay a little longer", "fork.knife", "Local life", "Talk over an imagined meal in Japan about routines and everyday customs. Compare individual experiences without assuming one tradition represents everyone.", 2)
        ]
    )
}
