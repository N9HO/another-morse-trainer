import Foundation

// Short public-domain passages (Aesop's fables, plainly retold) for the
// continuous-copy "Short Stories" mode. Kept short and free of apostrophes and
// quotes so the displayed text matches what can be sent in Morse cleanly;
// periods and commas are shown on reveal but skipped when keying.
extension MorseData {

    /// A bundled practice passage for continuous copy.
    public struct Story: Identifiable, Sendable, Equatable {
        public let id: String
        public let title: String
        public let text: String
        public init(id: String, title: String, text: String) {
            self.id = id
            self.title = title
            self.text = text
        }
        /// Rough length label for the picker (word count bucket).
        public var lengthLabel: String {
            let words = text.split(separator: " ").count
            if words <= 30 { return "short" }
            if words <= 55 { return "medium" }
            return "long"
        }
    }

    public static let stories: [Story] = [
        Story(id: "fox-grapes", title: "The Fox and the Grapes",
              text: "A hungry fox saw clusters of ripe grapes hanging high on a vine. He jumped again and again but could not reach them. At last he gave up and walked away, saying the grapes were surely sour."),
        Story(id: "tortoise-hare", title: "The Tortoise and the Hare",
              text: "A hare mocked a tortoise for being slow, so they agreed to race. The hare ran ahead and lay down to nap, sure of winning. The tortoise kept a steady pace and passed the sleeping hare to win."),
        Story(id: "lion-mouse", title: "The Lion and the Mouse",
              text: "A lion caught a tiny mouse but let it go. Later the lion was caught in a hunters net. The little mouse heard him roar and gnawed the ropes until the lion was free. Even the small can help the great."),
        Story(id: "crow-pitcher", title: "The Crow and the Pitcher",
              text: "A thirsty crow found a pitcher with a little water at the bottom, too low to reach. One by one she dropped in pebbles until the water rose to the top. Then she drank her fill. Patience and wit win the day."),
        Story(id: "ant-grasshopper", title: "The Ant and the Grasshopper",
              text: "All summer the ant stored grain while the grasshopper sang and played. When winter came the grasshopper was hungry and cold. The ant had plenty. It is wise to prepare today for the needs of tomorrow."),
        Story(id: "north-wind-sun", title: "The North Wind and the Sun",
              text: "The wind and the sun argued over who was stronger. They agreed the winner would make a traveler remove his coat. The wind blew hard but the man held tight. Then the sun shone warmly and he took it off."),
        Story(id: "dog-bone", title: "The Dog and the Bone",
              text: "A dog carried a bone across a bridge and saw his own shadow in the water below. Thinking it was another dog with a larger bone, he snapped at it. His own bone fell into the river and was lost."),
        Story(id: "golden-egg", title: "The Goose and the Golden Egg",
              text: "A farmer owned a goose that laid one golden egg each day. Greedy for more, he cut the goose open to take all the gold at once. He found nothing inside, and the goose was gone. Greed can ruin good fortune."),
        Story(id: "wolf-crane", title: "The Wolf and the Crane",
              text: "A wolf had a bone stuck in his throat and begged a crane for help. The crane reached in with her long beak and pulled it out. When she asked for her reward, the wolf only laughed and walked away."),
        Story(id: "oak-reeds", title: "The Oak and the Reeds",
              text: "A mighty oak stood proud beside a bed of slender reeds. A great storm came and the reeds bent low with the wind, but the stiff oak resisted and was torn up by the roots. Yielding can be its own strength."),
    ]
}
