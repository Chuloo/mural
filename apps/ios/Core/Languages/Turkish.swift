import Foundation

extension LanguageModule {
    public static let turkish = LanguageModule(
        id: "tr", name: "Turkish", nativeName: "Türkçe", variety: "Turkey", locale: "tr-TR",
        greeting: "Merhaba!", greetingWord: "merhaba",
        speechGuidance: "Use clear, natural Standard Turkish pronunciation. Use 'sen' for friendly conversation and 'siz' when the situation calls for formality. Accept valid regional accents and vocabulary without treating a regional difference or a non-native accent alone as an error. Do not imitate a regional caricature.",
        writingGuidance: "Use standard Turkish spelling with proper I/ı, İ/i, Ş/ş, Ç/ç, Ğ/ğ, Ö/ö, Ü/ü characters. Match the register to the situation and accept valid regional usage from the learner.",
        lemmaGuidance: "Give nouns in their singular form and verbs in the dictionary (mastar) form, for example ev and gelmek. Preserve vowel harmony patterns and meaningful suffixes. Keep compound verbs distinct.",
        teachingFocus: [
            "Greetings, introductions and useful everyday chunks such as benim adım and istiyorum.",
            "Everyday questions, vowel harmony, present tense and common sentence structures.",
            "Connected stories, past tense (miş/mış and di/di), future tense and familiar situations.",
            "Reasons and opinions, conditional clauses, reported speech and natural connectors.",
            "Nuance, hypothetical situations, passive and causal constructions, idiomatic phrasing.",
            "Flexible advanced discussion with precise, natural Turkish and appropriate tone."
        ],
        topicPlaceholder: "Yemek, seyahat, müzik, Türkiye'de hayat…",
        lookupUnavailableReply: "Şu anda bunu kontrol edemedim. İsterseniz konu hakkında genel olarak konuşabiliriz.",
        themeOverrides: [
            "coffee": .init("coffee", "Bir kahve?", "Sıcak bir şey lütfen", "cup.and.saucer", "Everyday", "Bir kahvehane veya kafede buluşun. İçecek sipariş edin ve sohbet edin. Öğrencinin ilgi alanlarını sorun.", 0),
            "groceries": .init("groceries", "Pazarda", "Biraz her şeyden", "basket", "Everyday", "Yerel bir pazarda veya markette alışveriş yapın. Miktar, fiyat ve nazik sorular pratik yapın.", 2),
            "travel": .init("travel", "Durak", "Bir yerlere bilet", "tram", "Everyday", "Türkiye'de bir gezi planlayın. Ulaşım ve biletler hakkında konuşun.", 1),
            "cabin": .init("cabin", "Bir hafta sonu kaçamağı", "Biraz huzur", "mountain.2", "Local life", "Hayali bir hafta sonu tatili planlayın: seyahat, yemek, yürüyüş ve birlikte dinlenme.", 2),
            "traditions": .init("traditions", "Gelenekler", "Küçük gelenekler, büyük hikayeler", "flag", "Local life", "Türkiye'deki günlük gelenekler ve bayramlar hakkında konuşun. Farklı bölgeleri karşılaştırın.", 2)
        ]
    )
}
