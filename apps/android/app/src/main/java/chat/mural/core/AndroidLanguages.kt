package chat.mural.core

/**
 * Language Modules this platform offers on top of the generated cross-platform registry.
 *
 * `Languages.kt` is generated from the iPhone language content, so a Language Module that ships on
 * Android first lives here until its Swift module exists. Android call sites resolve learning
 * languages through [all] and [get]; `LanguageRegistry` keeps the published cross-platform contract.
 *
 * shortcut: hosted (Mural Minutes) voice rejects a locale `services/api/src/live-provider.ts` does
 * not list; add pt-PT and ja-JP there and deploy before offering Portugal or Japan voice on that
 * provider.
 *
 * A Typed-only Module has no voice on any provider, so it needs neither: its whole conversation is
 * typed. Add its id to [typedOnlyIDs]; nothing else in the app decides this per module.
 */
object AndroidLanguages {
    val portuguesePortugal = LanguageModule(
        id = "pt-PT",
        name = "Portuguese",
        nativeName = "Português",
        variety = "Portugal",
        locale = "pt-PT",
        greeting = "Olá!",
        greetingWord = "olá",
        speechGuidance = "Use clear, natural European Portuguese as spoken in Portugal, with reduced unstressed vowels, syllable-final s and z pronounced as a sh sound, as in os amigos and estava, and enclitic pronoun placement such as diz-me, dá-me and vamos conversar. Use tu with friends and family, você or the third person without a pronoun in polite address, and o senhor or a senhora when formality fits; follow the learner's choice and accept all three as natural Portugal address. Accept valid regional Portuguese accents and wording, including Brazilian, African and island varieties, without marking them as errors. Do not imitate a regional caricature or infer a pronunciation error from a transcript alone.",
        writingGuidance = "Use standard contemporary European Portuguese spelling and accents, including á, é, í, ó, ú, â, ê, ô, ã, õ and ç. Prefer everyday Portugal wording such as autocarro, comboio, pequeno-almoço, telemóvel and casa de banho, and the progressive a + infinitivo, estou a fazer, rather than the Brazilian gerund. Keep object pronouns after the verb in affirmative sentences, diz-me and vejo-te, and before it where European usage requires, não me digas. Accept valid Brazilian spelling, vocabulary and gerund forms from the learner without calling them errors.",
        lemmaGuidance = "Give nouns with their singular article and verbs in the infinitive, for example o comboio, a chávena, o telemóvel and ir. Preserve accents, nasal vowels and ç. Keep hyphenated clitic forms readable: quote diz-me as the observed form and give dizer as the lemma. Keep reflexive and pronominal verbs such as lembrar-se and esquecer-se distinct. Use a consistent European dictionary form without treating Brazilian alternatives as errors.",
        teachingFocus = listOf(
            "Greetings, introductions and useful everyday chunks such as olá, bom dia, faz favor and com licença.",
            "Everyday questions, gender and number agreement, the present tense, ser and estar, and natural tu or você address.",
            "Connected stories, pretérito perfeito and imperfeito in context, plans and familiar situations, with pronouns after the verb such as disse-me.",
            "Reasons and opinions, the a + infinitivo progressive, object pronoun placement, polite requests and common subjunctive contexts.",
            "Nuance, future subjunctive, personal infinitive, hypothetical situations, idiomatic European phrasing and register.",
            "Flexible advanced discussion with precise, natural European Portuguese and appropriate tone."),
        topicPlaceholder = "Comida, música, viagens, a vida em Portugal…",
        lookupUnavailableReply = "Não consegui verificar isso agora. Se quiseres, podemos falar sobre o assunto de forma geral.",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "Uma bica?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood pastelaria in Portugal. Order a bica and a pastel de nata, then chat about the learner's day with natural Portugal address.", 0),
            "groceries" to ConversationTheme("groceries", "No mercado", "A little of everything", "basket", "Everyday", "Shop at a Portuguese market or a small mercearia. Practise quantities, prices and polite requests such as faz favor, using European product names.", 2),
            "travel" to ConversationTheme("travel", "De comboio ou de elétrico", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Portugal by CP comboio, metro or a Lisbon elétrico. Discuss routes and tickets without inventing current schedules or fares.", 1),
            "walk" to ConversationTheme("walk", "Um passeio", "Out into the fresh air", "tree", "Local life", "Take an imagined walk in Portugal, up to a miradouro or along the riverfront. Talk about the view, the weather and everyday life.", 2),
            "cabin" to ConversationTheme("cabin", "Um fim de semana fora", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in Portugal: the Alentejo, the Douro valley or the coast. Discuss travel, food, walks and relaxing together.", 2),
            "restaurant" to ConversationTheme("restaurant", "Jantar numa tasca", "Stay for dessert", "wineglass", "Everyday", "Role-play dinner in a Portuguese tasca or restaurante. Practise requests, the menu and polite problem-solving with natural European address.", 0),
            "traditions" to ConversationTheme("traditions", "Santos Populares", "Small customs, big stories", "flag", "Local life", "Talk about Portuguese traditions such as the Santos Populares in June, sardines, fado or a family meal. Compare individual customs without treating Portugal or Portuguese-speaking cultures as uniform.", 2))
    )
    val romanKonkani = LanguageModule(
        id = "kok-Latn",
        name = "Konkani",
        nativeName = "Konknni",
        variety = "Roman script",
        locale = "kok-Latn",
        greeting = "Dev borem korum!",
        greetingWord = "dev borem korum",
        speechGuidance = "Konkani has no speech recognition and no text-to-speech voice on any provider, cloud or on the phone, so this conversation is typed only: never claim to have heard audio, never judge pronunciation, and never ask the learner to say something aloud or repeat it. Judge only what is written. Keep the rhythm and phrasing of natural spoken Romi Konknni as it is written in Goa and Mangalore: short warm sentences, everyday connectors such as ani, punn, zalear and mhunnon, and the direct question style of a tiatr scene. Treat the learner's own regional Romi Konknni and their everyday borrowings from English, Hindi and Marathi as valid Konkani, never as errors.",
        writingGuidance = "Write Konkani in the Roman script only, the way Romi Konknni is written in Goa and Mangalore; never write Devanagari or Kannada. Follow everyday Romi Konknni spelling, for example hanv, tujea, amchem, mhaka, kiteak, khoim, kedna, jevon, bazar, udk, pavs, zatra and Dev borem korum. Accept natural orthographic variation across Goan and Mangalorean Romi Konknni, such as mhaka or maka for 'to me' and the vowel spellings that differ between the two coasts, without calling either form wrong. Accept the everyday code-switching and borrowings from English, Hindi and Marathi that real Konkani speakers write — bus, time, office, school, dukaan, kam — as valid Konkani, never as learner mistakes. Correct only a form that is wrong in the learner's own variety.",
        lemmaGuidance = "Give each word its Roman-script Konkani base form: verbs in the infinitive such as korunk, vochunk and khawunk, nouns in the singular such as kafe, bazar, jevon and udk. Never return a Devanagari or Kannada lemma for a Roman-script quote, and keep the learner's own Roman spelling in quote and form. Treat everyday borrowings from English, Hindi and Marathi written inside a Konkani sentence — bus, time, dukaan, school, kam — as valid Konkani vocabulary: tag them kok-Latn instead of dropping them or labelling them as another language, never as errors. Use en, mixed or uncertain only when the learner clearly switched language for a whole clause. Keep one consistent Roman dictionary form and never treat a Goan or Mangalorean spelling variant as an error.",
        teachingFocus = listOf(
            "Greetings and short everyday chunks such as Dev borem korum, Koxem asa?, Borem asa and Mhojem nanv.",
            "Simple typed questions and answers, present forms, gender and number, such as ek kafe, mhaka zai and tujea ghorant.",
            "Connected short turns about jevon, bazar and ghor, past forms and everyday connectors such as ani, punn and mhunnon.",
            "Reasons, opinions and polite requests, kiteak and zalear clauses, and natural code-switching with English, Hindi and Marathi words.",
            "Nuance, register and idiomatic Romi Konknni, including tiatr, zatra and pavsa references, in longer written turns.",
            "Flexible advanced written discussion with precise, natural Romi Konknni and appropriate tone."),
        topicPlaceholder = "Kafe, jevon, bazar, pavs, zatra, ghor…",
        lookupUnavailableReply = "Maka hem attam polleunk zalem na. Zai zalear ami hea vixoyar sadheponnan uloia.",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "Kafe vo chá?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a Goan kafe or a roadside stall. Order a chá or a kafe and a fresh pao, then chat about the learner's day in natural Romi Konknni, using the address they choose.", 0),
            "groceries" to ConversationTheme("groceries", "Bazarant", "A little of everything", "basket", "Everyday", "Shop at a Konkani bazar or a small dukan. Practise quantities, prices and polite requests, using Konkani names for fish, rice, coconut and vegetables.", 2),
            "travel" to ConversationTheme("travel", "Bos vo feri", "A ticket to somewhere", "tram", "Everyday", "Plan a trip by a local bos or the Mandovi feri. Discuss routes and tickets without inventing current schedules or fares.", 1),
            "dinner" to ConversationTheme("dinner", "Ghorchem jevon", "Let’s make something", "fork.knife", "Everyday", "Plan a family jevon at home. Ask about the ingredients, who cooks what and the steps, from rice and fish curry to sanna and neureo.", 3),
            "walk" to ConversationTheme("walk", "Doriya lagim", "Out into the fresh air", "tree", "Local life", "Take an imagined walk along a Goan or Mangalorean shore, or through the lanes behind the church or the temple. Talk about the sea, the weather and everyday life.", 2),
            "weather" to ConversationTheme("weather", "Pavs", "Whatever the weather", "cloud.rain", "Local life", "Talk about the monsoon pavs, the heat before it and what it changes at home and at work. Verify a forecast before claiming today's weather.", 1),
            "traditions" to ConversationTheme("traditions", "Zatra ani Fest", "Small customs, big stories", "flag", "Local life", "Talk about Konkani traditions such as a zatra, a fest, Konsachem Fest, Shigmo, tiatr or a family jevon. Compare individual customs without treating Goan, Mangalorean or East Indian Konkani communities as uniform.", 2))
    )
    val jamaicanPatois = LanguageModule(
        id = "jam",
        name = "Jamaican Patois",
        nativeName = "Patwa",
        variety = "Jamaica",
        locale = "jam",
        greeting = "Wah gwaan!",
        greetingWord = "wah gwaan",
        speechGuidance = "Jamaican Patois (Patwa) has no speech recognition and no text-to-speech voice on any provider, cloud or on the phone, so this conversation is typed only: never claim to have heard audio, never judge pronunciation, and never ask the learner to say something aloud or repeat it. Judge only what is written. Keep the cadence and idiom of natural spoken Patwa as Jamaicans write it: short warm turns, the tense-aspect markers a, did and ago, the pronouns mi, yu, im, shi, ihn, wi, unu and dem, plural dem after the noun, the copula and focus marker a as in a mi dat, the locative de or deh as in mi deh ya, and everyday openers such as Wah gwaan, Mi deh yah, Likkle more and Walk gud. Patwa is a language with its own grammar and sound system, not broken English: never rewrite it as an English sentence with words dropped, never judge how far the learner moves along the Jamaican Patois and Standard Jamaican English continuum, and treat their natural code-switching and English borrowings as valid Patwa, never as errors. Never imitate a stage caricature of Jamaica or a stereotype.",
        writingGuidance = "Write Jamaican Patois in its own orthography, the way Jamaicans write Patwa, and accept both conventions: the Cassidy-JLU phonemic spelling of the Jamaican Language Unit, which writes wah gwaan, mi de ya, did, ago and di, and the popular Jamaican spelling Jamaicans use in texts and on social media, which writes wa gwaan, mi deh yah, inna, yaad and soon come. Keep the learner's own convention inside one word and never rewrite it into the other. Follow Patwa grammar, not English grammar: a for the progressive and habitual, mi a nyam; did, wen or ben for the past, im did tell mi; ago or a go for what is going to happen, wi a-go come; nuh and no for negation, mi nuh know; seh to introduce what someone said; fi for 'to' and 'for'; di for 'the'; wan for 'a' or 'one'; the pronouns mi, yu, im, shi, ihn, wi, unu and dem; dem after a noun for the plural, di pikni dem; a as the copula and focus marker, a mi dat; and de or deh for 'is at', mi deh ya. Patwa is a language with its own grammar and sound system, not broken English: never correct it into Standard Jamaican English, never call a missing English ending an error, and treat the learner's natural code-switching with English along the Jamaican Patois and Standard Jamaican English continuum as valid Patwa, never as a learner mistake. Correct only a form that is wrong inside Patwa itself.",
        lemmaGuidance = "Give each word its Patwa base form: verbs as the bare stem that follows a tense marker, for example nyam, go, kom, si and tel, never an English infinitive with 'to'; nouns in the singular without the plural dem, for example pikni, baks, market, rain and song; pronouns as mi, yu, im, shi, ihn, wi, unu and dem; and the tense-aspect markers a, did, wen and ago as their own entries. Never return an English lemma for a Patwa quote, and keep the learner's own spelling in quote and form, whether Cassidy-JLU or popular Jamaican, so that deh and de, wah and wa, and yaad and yard are one word and never errors. Treat everyday English words written inside a Patwa sentence — bus, phone, school, work, money, doctor — as valid vocabulary for this module: tag them jam instead of dropping them or labelling them en, because code-switching along the Jamaican Patois and Standard Jamaican English continuum is how real Jamaicans write and Patwa is not broken English. Use en, mixed or uncertain only when the learner clearly switched to Standard Jamaican English for a whole clause.",
        teachingFocus = listOf(
            "Greetings and short everyday chunks such as Wah gwaan!, Mi deh yah, Bless up, Likkle more and Walk gud.",
            "Simple typed questions and answers with di, wan and the pronouns mi, yu, im, wi and unu, such as Wah yu a do? and Mi want wan cup a tea.",
            "Connected short turns about nyamin, market and di yaad, the markers a, did and ago, serial verbs such as go buy and kom look, and plural dem as in di pikni dem.",
            "Reasons, opinions and polite requests, wah mek and seh clauses, focus a as in a mi dat, negation with nuh and no, and natural code-switching with English.",
            "Nuance, register and idiomatic Patwa, including proverb, Miss Lou's poems, music, market day and rain, in longer written turns.",
            "Flexible advanced written discussion with precise, natural Jamaican Patois and appropriate tone."),
        topicPlaceholder = "Nyamin, song, market day, rain, di yaad, travel…",
        lookupUnavailableReply = "Mi kudn check dat right now. If yu waan, wi can reason bout it inna general.",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "Wan cup a coffee?", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a Jamaican corner shop or a cookshop. Order a cup a Blue Mountain coffee or a cup a tea with hard dough bread and ackee an saltfish, then reason bout di learner's day in natural Patwa, using the address dem choose.", 0),
            "groceries" to ConversationTheme("groceries", "Market day", "A little of everything", "basket", "Everyday", "Shop at Coronation Market or a country market. Practise quantities, prices and bargaining, and name Jamaican food: ackee, callaloo, scotch bonnet, green banana, breadfruit, yam and coco.", 2),
            "travel" to ConversationTheme("travel", "Route taxi an Knutsford", "A ticket to somewhere", "tram", "Everyday", "Plan a trip from Half Way Tree by route taxi and minibus, or cross-country by Knutsford Express. Discuss routes, fares and where yu a go without inventing current schedules or prices.", 1),
            "dinner" to ConversationTheme("dinner", "Sunday dinner", "Let’s make something", "fork.knife", "Everyday", "Plan Sunday dinner at di yaad: rice and peas with oxtail, curry goat, stew chicken, jerk or steamed fish, with festival or bammy. Ask who a cook wah, the ingredients and the steps.", 3),
            "walk" to ConversationTheme("walk", "Verandah an di yaad", "Out into the fresh air", "tree", "Local life", "Sit on di verandah or walk bout di yaad and di lane: the breeze, the neighbours, the hill and the sea. Talk about the weather and everyday life in Jamaica.", 2),
            "weather" to ConversationTheme("weather", "Rain a fall", "Whatever the weather", "cloud.rain", "Local life", "Talk about di rain and di heat, hurricane season, the breeze at night and wah rain change at di yaad and a work. Never claim today's weather without checking it.", 1),
            "music" to ConversationTheme("music", "Reggae an dancehall", "What are you listening to?", "music.note", "Interests", "Reason bout music: reggae, dancehall, ska, rocksteady, dub, a sound system or a riddim the learner love. Ask bout feeling, favourite artistes and di songs dem grow up on.", 0),
            "traditions" to ConversationTheme("traditions", "Jonkonnu an Miss Lou", "Small customs, big stories", "flag", "Local life", "Talk about Jamaican customs: Jonkonnu, sorrel and Christmas cake, Emancipation Day and Independence Day, Labour Day, Kumina, Anansi story and Miss Lou's Patwa poems. Compare individual customs without treating Jamaicans or di diaspora as one same.", 2),
            "opinions" to ConversationTheme("opinions", "Wi a reason", "Room for another view", "quote.bubble", "Connection", "Pick an everyday dilemma — whether di bus fare should rise, whether pikni should answer back, whether yu cook stew chicken with coconut milk — and reason it out. Invite wah mek and gently explore another view.", 1))
    )
    val japanese = LanguageModule(
        id = "ja",
        name = "Japanese",
        nativeName = "日本語",
        variety = "Japan",
        locale = "ja-JP",
        greeting = "こんにちは！",
        greetingWord = "こんにちは",
        speechGuidance = "Use clear, natural standard Japanese pronunciation as spoken in Japan: even mora timing, with each kana given roughly the same length; long vowels such as おかあさん, おおさか and ゆうき; the geminate っ in きっと, がっこう and ちょっと; and pitch accent where it distinguishes meaning, as in はし for chopsticks or bridge, あめ for rain or candy, and 東京 against 京都. Let the natural devoicing of the vowel in です and ます happen without losing its mora. Default to warm, polite everyday です・ます speech, and adapt naturally when the learner answers in casual plain form such as だよ or 行こう: follow their register instead of pulling them back. Accept learner accents and valid regional phrasing and wording, including Kansai forms such as ほんま and おおきに, without treating a regional difference or a non-native accent alone as an error. Never imitate a regional caricature, and never infer a pronunciation or pitch error from a transcript alone.",
        writingGuidance = "Write natural modern Japanese, mixing Kanji, Hiragana and Katakana the way everyday Japanese does, with no spaces between words: 今日は喫茶店でコーヒーを飲みました。 Use standard Japanese punctuation — 。 to end a sentence, 、 inside one, ？ and ！ where they fit naturally, and 「」 for quoted speech. Keep ordinary Kanji usage, writing ください and ありがとう in kana as Japanese normally does, and Katakana for loanwords such as コーヒー, パン and アルバイト. Accept the learner's kana-only text, half-width characters and casual typed style without calling them errors. Do not append romaji, furigana or an English translation to ordinary replies; give a reading such as 富士山（ふじさん） only when the learner asks for one.",
        lemmaGuidance = "Give vocabulary lemmas in standard Japanese dictionary plain form: verbs as 食べる, 行く, 飲む and する, い-adjectives as 静か and 高い, な-adjectives as 元気, nouns as 電車, 喫茶店 and 日本語, with no romaji and no English in the lemma. Keep the exact observed conjugated or kana/kanji form in form and quote, so 飲みました keeps its own spelling with 飲む as the lemma, and のみました stays kana. Keep meaningful expressions intact as one entry, such as 気をつける, お願いします, お疲れさま and いただきます. A polite ます form and its plain form share one lemma. Never treat the learner's kana-only spelling or a valid regional word as an error, and never infer spoken recall or pitch accuracy from a transcript alone.",
        teachingFocus = listOf(
            "Greetings, self-introductions and useful everyday chunks such as こんにちは, はじめまして, 〜をください and すみません.",
            "Everyday questions, the particles は, が, を, に, で, と and も, numbers, and the present and past ます forms such as 行きます and 行きました.",
            "Connected stories, the て form joining events as in 飲んで、それから, past and progressive forms, plans and familiar situations.",
            "Reasons and opinions, plain forms, 〜と思う, 〜から and 〜ので, polite requests and natural casual speech.",
            "Nuance, conditionals such as 〜たら and 〜ば, giving and receiving with あげる, もらう and くれる, keigo basics such as いらっしゃいます and 伺う, and idiomatic phrasing.",
            "Flexible advanced discussion with precise, natural Japanese and appropriate tone and register."),
        topicPlaceholder = "食べ物、音楽、旅行、日本の暮らし…",
        lookupUnavailableReply = "今はそれを確認できません。よければ、この話題について一般的に話しましょう。",
        themeOverrides = mapOf(
            "coffee" to ConversationTheme("coffee", "喫茶店で一息？", "Something warm, please", "cup.and.saucer", "Everyday", "Meet at a neighbourhood Japanese kissaten, 喫茶店. Order a コーヒー or a ミックスジュース with ください, then chat about the learner's day in warm です・ます Japanese.", 0),
            "introductions" to ConversationTheme("introductions", "はじめまして", "Start somewhere small", "hand.wave", "Connection", "Meet the learner for the first time in Japanese. Practise a short 自己紹介: their name, where they live and what they like, closing with よろしくお願いします.", 0),
            "groceries" to ConversationTheme("groceries", "商店街で買い物", "A little of everything", "basket", "Everyday", "Shop in a Japanese shotengai, 商店街, or a supermarket. Practise quantities, prices and polite requests such as いくらですか and ください, with everyday names: 豆腐, ねぎ, しょうゆ, みそ.", 2),
            "travel" to ConversationTheme("travel", "JRと地下鉄で", "A ticket to somewhere", "tram", "Everyday", "Plan a trip in Japan by JR line, 地下鉄 or 新幹線. Discuss routes, 乗り換え, a 切符 or charging an IC card without inventing current schedules or fares.", 1),
            "walk" to ConversationTheme("walk", "公園を散歩", "Out into the fresh air", "tree", "Local life", "Take an imagined walk in a Japanese park, under the 桜 during 花見 or past a 神社. Talk about the season, the weather and everyday life.", 2),
            "cabin" to ConversationTheme("cabin", "週末の小旅行", "A change of scene", "mountain.2", "Local life", "Plan an imagined weekend away in Japan: a 旅館 with an 温泉, 箱根, 京都 or the 瀬戸内 sea. Discuss travel, 料理, walks and relaxing together.", 2),
            "restaurant" to ConversationTheme("restaurant", "定食か居酒屋で", "Stay for dessert", "wineglass", "Everyday", "Role-play a meal in a Japanese 定食屋 or 居酒屋. Practise ordering with ください and おすすめ, the menu, 取り分け and polite problem-solving.", 0),
            "traditions" to ConversationTheme("traditions", "季節の行事", "Small customs, big stories", "flag", "Local life", "Talk about Japanese seasonal customs such as お正月, 節分, 花見, 七夕, お盆 and 年末の大掃除, or a family meal. Compare individual customs without treating Japan or Japanese people as uniform.", 2))
    )
    val extraModules: List<LanguageModule> = listOf(portuguesePortugal, romanKonkani, jamaicanPatois, japanese)
    /** Language Modules with no voice anywhere: both sides of the conversation are typed. */
    val typedOnlyIDs: Set<String> = setOf("kok-Latn", "jam")
    val all: List<LanguageModule> = LanguageRegistry.all + extraModules
    fun get(id: String): LanguageModule? = all.firstOrNull { it.id == id }
}

val LanguageModule.isTypedOnly: Boolean get() = id in AndroidLanguages.typedOnlyIDs
