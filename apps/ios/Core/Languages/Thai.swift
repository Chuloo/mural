import Foundation

extension LanguageModule {
    public static let thai = LanguageModule(
        id: "th", name: "Thai", nativeName: "ภาษาไทย", variety: "Thailand", locale: "th-TH",
        greeting: "สวัสดี!", greetingWord: "สวัสดี",
        speechGuidance: "Use clear, natural Central Thai pronunciation. Treat tones, vowel length, final consonants and polite particles as meaningful when they affect understanding. Accept valid regional accents and vocabulary without treating a regional difference or a non-native accent alone as an error. Do not imitate a regional caricature.",
        writingGuidance: "Use natural modern Thai with Thai script and appropriate spacing between phrases. Keep polite particles and pronouns natural to the learner’s context. Do not append transliteration or translations to ordinary spoken replies; explain pronunciation briefly only when asked.",
        lemmaGuidance: "Give vocabulary in natural Thai script and preserve the exact observed form and quote. Prefer useful dictionary forms or everyday chunks, and include polite particles only when they are part of the meaningful expression. Do not infer tone or pronunciation accuracy from typed text alone.",
        teachingFocus: [
            "Greetings, introductions, names, polite particles and useful everyday chunks such as ผมชื่อ and ฉันอยากได้.",
            "Everyday questions, classifiers, numbers, food and short present-time exchanges.",
            "Connected stories, completed actions, time expressions and familiar situations.",
            "Reasons and opinions, comparisons, requests and natural linking phrases.",
            "Nuance, conditionals, register, particles and regional variation.",
            "Flexible advanced discussion with precise, natural Thai and context-appropriate politeness."
        ],
        topicPlaceholder: "อาหาร การเดินทาง เพลง ชีวิตประจำวัน…",
        lookupUnavailableReply: "ตอนนี้ฉันยังตรวจสอบเรื่องนี้ไม่ได้ ถ้าคุณต้องการ เราคุยเกี่ยวกับภาพรวมของหัวข้อนี้ก่อนได้นะ",
        themeOverrides: [
            "coffee": .init("coffee", "ดื่มกาแฟกันไหม", "Something warm, please", "cup.and.saucer", "Everyday", "Meet in a neighbourhood café in Thailand. Order a drink and chat in Thai, following the learner’s interests.", 0),
            "groceries": .init("groceries", "ไปตลาดกัน", "Find something good", "basket", "Everyday", "Shop for everyday ingredients at a Thai market or supermarket. Practise quantities, prices and polite questions while respecting regional food vocabulary.", 2),
            "travel": .init("travel", "สถานีต่อไป", "A ticket to somewhere", "tram", "Everyday", "Plan an imagined trip in Thailand. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "cabin": .init("cabin", "เที่ยวสุดสัปดาห์", "A change of scene", "mountain.2", "Local life", "Imagine a weekend away in a city, by the sea or in the countryside. Discuss practical plans and things the learner enjoys.", 2),
            "traditions": .init("traditions", "เรื่องเล็ก ๆ ในชีวิตประจำวัน", "Small customs, big stories", "flag", "Local life", "Talk in Thai about everyday customs and festivals. Compare experiences without treating any habit as universal.", 2)
        ]
    )
}
