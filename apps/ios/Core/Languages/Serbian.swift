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
            "weekend": .init("weekend", "Kako je bilo za vikend?", "Tell me about yours", "sun.horizon", "Connection", "Ask about the learner’s weekend. Practise past events and follow their interests.", 1),
            "walk": .init("walk", "Mala šetnja", "Out into the fresh air", "tree", "Local life", "Take an imagined walk together along a river bank or through a park in Serbia. Talk about nature, weather and daily life.", 2),
            "dinner": .init("dinner", "Šta ćemo za večeru?", "Let’s make something", "fork.knife", "Everyday", "Plan dinner together, perhaps a familiar Serbian dish or something new. Ask about ingredients, preferences and the steps of cooking.", 3),
            "introductions": .init("introductions", "Drago mi je", "Start somewhere small", "hand.wave", "Connection", "Meet the learner for the first time. Learn their interests through natural introductions.", 0),
            "groceries": .init("groceries", "Na pijaci", "A little of everything", "basket", "Everyday", "Visit an open-air green market in Serbia. Practise quantities, prices and polite requests, then ask what the learner likes to cook.", 2),
            "travel": .init("travel", "Na putu", "A ticket to somewhere", "tram", "Everyday", "Plan a trip around Serbia by bus or train. Discuss transport, directions and tickets without inventing current schedules.", 1),
            "home": .init("home", "Svoj kutak", "Make yourself at home", "house", "Everyday", "Discuss a home, rooms, moving and what makes a place comfortable.", 3),
            "friends": .init("friends", "Novi prijatelji", "An invitation, maybe", "person.2", "Connection", "You are a friendly new acquaintance. Arrange something to do together, such as a coffee or a walk.", 0),
            "work": .init("work", "Ponedeljak ujutru", "Around the office", "briefcase", "Everyday", "Chat as colleagues. Discuss work, meetings and a small problem to solve.", 1),
            "weather": .init("weather", "Opet pada kiša?", "Whatever the weather", "cloud.rain", "Local life", "Talk about weather, clothing and outdoor plans. Do not claim today’s forecast without sources.", 1),
            "cabin": .init("cabin", "Vikend van grada", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in Serbia. Choose a city, the mountains or the countryside together and discuss practical plans.", 2),
            "music": .init("music", "Šta slušaš?", "What are you listening to?", "music.note", "Interests", "Ask about music the learner enjoys. Explore feelings, favourites and concerts.", 0),
            "film": .init("film", "Još jedna epizoda", "Something worth watching", "film", "Interests", "Discuss films and series. Ask for opinions and avoid unwanted spoilers.", 1),
            "books": .init("books", "Između korica", "A story that stayed", "book", "Interests", "Chat about books, characters, stories and why they matter to the learner.", 3),
            "design": .init("design", "Lepe stvari", "Made with a little care", "pencil.and.outline", "Interests", "Explore design, architecture and objects the learner loves. Ask for concrete opinions.", 0),
            "technology": .init("technology", "Šta nas čeka", "Ideas, tools and tomorrow", "sparkles", "Interests", "Discuss technology and how it changes daily life. Delegate claims needing current facts.", 1),
            "travelstories": .init("travelstories", "Negde drugde", "A place you remember", "globe.europe.africa", "Interests", "Exchange travel stories and dream destinations. Invite descriptions and comparisons.", 2),
            "restaurant": .init("restaurant", "Sto za dvoje", "Stay for dessert", "wineglass", "Everyday", "Role-play a meal at a restaurant or kafana in Serbia. Practise requests, preferences and polite problem-solving.", 0),
            "neighbours": .init("neighbours", "Komšije", "A familiar face", "building.2", "Connection", "Chat as neighbours in an apartment building in Serbia. Discuss the neighbourhood and small requests for help.", 3),
            "traditions": .init("traditions", "Slava", "A family feast day", "flame", "Local life", "Talk about slava, the family saint's day many Serbian families celebrate, and other everyday customs. Ask about the learner's own traditions and do not treat everyone in Serbia as alike.", 2),
            "opinions": .init("opinions", "Šta ti misliš?", "Room for another view", "quote.bubble", "Connection", "Choose an everyday dilemma. Invite reasons and gently explore another perspective.", 1),
            "future": .init("future", "Za godinu dana", "Plans worth talking about", "paperplane", "Connection", "Talk about hopes and future plans. Explore possibilities and practical next steps.", 3),
            "today": .init("today", "Svet danas", "Something to talk about", "newspaper", "Interests", "Ask what current topic interests the learner, then delegate a source-backed lookup before discussing facts.", 0)
        ]
    )
}
