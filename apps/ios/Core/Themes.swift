import Foundation

public struct ConversationTheme: Identifiable, Hashable, Sendable {
    public var id: String
    public var title: String
    public var subtitle: String
    public var symbol: String
    public var category: String
    public var situation: String
    public var colorIndex: Int
    public init(_ id: String, _ title: String, _ subtitle: String, _ symbol: String, _ category: String, _ situation: String, _ colorIndex: Int) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.symbol = symbol
        self.category = category; self.situation = situation; self.colorIndex = colorIndex
    }
    public static let shared: [Self] = [
        .init("coffee", "A coffee?", "Something warm, please", "cup.and.saucer", "Everyday", "You work in a cosy café. Help the learner order, then chat naturally.", 0),
        .init("weekend", "The weekend", "Tell me about yours", "sun.horizon", "Connection", "Ask about the learner’s weekend. Practise past events and follow their interests.", 1),
        .init("walk", "A little walk", "Out into the fresh air", "tree", "Local life", "Take an imagined forest walk together. Talk about nature, weather and daily life.", 2),
        .init("dinner", "Dinner plans", "Let’s make something", "fork.knife", "Everyday", "Plan dinner together. Ask about ingredients, preferences and the steps of cooking.", 3),
        .init("introductions", "Nice to meet you", "Start somewhere small", "hand.wave", "Connection", "Meet the learner for the first time. Learn their interests through natural introductions.", 0),
        .init("groceries", "At the market", "Find the good tomatoes", "basket", "Everyday", "Help the learner shop at a local food market. Practise quantities and questions.", 2),
        .init("travel", "Next stop", "A ticket to somewhere", "tram", "Everyday", "Plan a train trip. Discuss routes and tickets without inventing real current schedules.", 1),
        .init("home", "A place of your own", "Make yourself at home", "house", "Everyday", "Discuss a home, rooms, moving and what makes a place comfortable.", 3),
        .init("friends", "New friends", "An invitation, maybe", "person.2", "Connection", "You are a friendly new acquaintance. Arrange something to do together.", 0),
        .init("work", "Monday morning", "Around the office", "briefcase", "Everyday", "Chat as colleagues. Discuss work, meetings and a small problem to solve.", 1),
        .init("weather", "Rain again?", "Whatever the weather", "cloud.rain", "Local life", "Talk about weather, clothing and outdoor plans. Do not claim today’s forecast without sources.", 1),
        .init("cabin", "A weekend away", "A quieter kind of day", "mountain.2", "Local life", "Plan a weekend away: travel, food, walks and relaxing together.", 2),
        .init("music", "On repeat", "What are you listening to?", "music.note", "Interests", "Ask about music the learner enjoys. Explore feelings, favourites and concerts.", 0),
        .init("film", "One more episode", "Something worth watching", "film", "Interests", "Discuss films and series. Ask for opinions and avoid unwanted spoilers.", 1),
        .init("books", "Between the pages", "A story that stayed", "book", "Interests", "Chat about books, characters, stories and why they matter to the learner.", 3),
        .init("design", "Good things", "Made with a little care", "pencil.and.outline", "Interests", "Explore design, architecture and objects the learner loves. Ask for concrete opinions.", 0),
        .init("technology", "What comes next", "Ideas, tools and tomorrow", "sparkles", "Interests", "Discuss technology and how it changes daily life. Delegate claims needing current facts.", 1),
        .init("travelstories", "Somewhere else", "A place you remember", "globe.europe.africa", "Interests", "Exchange travel stories and dream destinations. Invite descriptions and comparisons.", 2),
        .init("restaurant", "A table for two", "Stay for dessert", "wineglass", "Everyday", "Role-play a restaurant meal. Practise requests, preferences and polite problem-solving.", 0),
        .init("neighbours", "Next door", "A familiar face", "building.2", "Connection", "Chat as neighbours. Discuss the neighbourhood and small requests for help.", 3),
        .init("traditions", "Everyday customs", "Small customs, big stories", "flag", "Local life", "Explore everyday customs with nuance. Avoid treating a whole culture as alike.", 2),
        .init("opinions", "What do you think?", "Room for another view", "quote.bubble", "Connection", "Choose an everyday dilemma. Invite reasons and gently explore another perspective.", 1),
        .init("future", "A year from now", "Plans worth talking about", "paperplane", "Connection", "Talk about hopes and future plans. Explore possibilities and practical next steps.", 3),
        .init("today", "The world today", "Something to talk about", "newspaper", "Interests", "Ask what current topic interests the learner, then delegate a source-backed lookup before discussing facts.", 0)
    ]
}
