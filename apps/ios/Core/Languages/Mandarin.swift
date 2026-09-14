import Foundation

extension LanguageModule {
    public static let mandarin = LanguageModule(
        id: "zh", name: "Mandarin Chinese", nativeName: "普通话", variety: "Mainland China", locale: "zh-CN",
        greeting: "你好！", greetingWord: "你好",
        speechGuidance: "Use clear, natural Standard Mandarin pronunciation. Treat tones, tone changes, retroflex and non-retroflex sounds, and distinctions between initials and finals as meaningful when they affect understanding. Accept valid regional accents and vocabulary without treating a regional difference or a non-native accent alone as an error. Do not imitate a regional caricature.",
        writingGuidance: "Use natural Simplified Chinese and standard modern punctuation. Prefer everyday Mainland usage while accepting valid regional wording and Traditional Chinese input. Keep Chinese text free of unnecessary spaces. The app displays pinyin separately; do not append pinyin or translations to ordinary spoken replies. Explain characters and tones briefly in Mandarin when asked.",
        lemmaGuidance: "Give vocabulary lemmas in simplified characters only, with no pinyin or English in the lemma; the app supplies pronunciation help separately. Keep the exact observed form and quote, including Traditional Chinese or learner-written pinyin. Use dictionary forms and preserve meaningful chunks such as 洗澡 and 见面. Do not infer tone accuracy, pronunciation or spoken recall from typed pinyin or a transcript alone.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as 我叫 and 我想要.",
            "Everyday questions, word order, measure words, numbers and common present-time exchanges.",
            "Connected stories, completed actions with 了, experiences with 过, and familiar situations.",
            "Reasons and opinions, comparisons, 把 and 被 constructions, and natural linking phrases.",
            "Nuance, aspect, conditionals, idiomatic phrasing, register and regional variation.",
            "Flexible advanced discussion with precise, natural Mandarin and appropriate tone."
        ],
        topicPlaceholder: "Food, travel, films, everyday life…",
        lookupUnavailableReply: "我现在没法查证这件事。如果你愿意，我们可以先聊聊这个话题的一般情况。",
        themeOverrides: [
            "coffee": .init("coffee", "喝杯咖啡？", "Something warm, please", "cup.and.saucer", "Everyday", "在一家社区咖啡馆见面。用普通话点饮料并聊天，跟着学习者的兴趣展开对话。", 0),
            "groceries": .init("groceries", "去买菜", "Find something good", "basket", "Everyday", "在菜市场或超市买日常食材。练习数量、价格和礼貌的提问，尊重不同地区的食物词汇。", 2),
            "travel": .init("travel", "下一站", "A ticket to somewhere", "tram", "Everyday", "用普通话计划一次旅行。讨论交通、方向和买票，不要编造当前的时刻表。", 1),
            "cabin": .init("cabin", "周末出游", "A change of scene", "mountain.2", "Local life", "一起设想一个周末旅行，选择城市、海边或乡村，讨论实际安排和喜欢做的事情。", 2),
            "traditions": .init("traditions", "日常习俗", "Small customs, big stories", "flag", "Local life", "用普通话聊日常习俗和节日。比较学习者熟悉的地方，避免把任何一种习惯说成所有人的共同体验。", 2)
        ]
    )
}
