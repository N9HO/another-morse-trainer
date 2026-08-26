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
        Story(id: "boy-cried-wolf", title: "The Boy Who Cried Wolf",
              text: "A shepherd boy grew bored and shouted wolf, wolf, and the villagers came running for nothing. He played the trick twice more and laughed each time. When a real wolf came at last, nobody believed his cries. A liar is not trusted even when he tells the truth."),
        Story(id: "town-mouse", title: "The Town Mouse and the Country Mouse",
              text: "A town mouse visited his cousin in the country and scoffed at the plain food there. He led him to a fine city house full of cakes, but a cat and a dog chased them from every dish. The country mouse went home, saying a crust in peace beats a feast in fear."),
        Story(id: "fox-crow", title: "The Fox and the Crow",
              text: "A crow sat on a branch with a piece of cheese in her beak. A fox praised her glossy feathers and asked to hear her lovely voice. Flattered, the crow opened her beak to sing and the cheese fell. The fox snapped it up and trotted away. Beware of flatterers."),
        Story(id: "wolf-sheepskin", title: "The Wolf Dressed as a Sheep",
              text: "A wolf wrapped himself in a sheepskin and slipped into the flock unseen. The shepherd counted his sheep at dusk and shut the gate. That night he went to fetch mutton for his table and took the wolf by mistake. The cheat was undone by his own disguise."),
        Story(id: "frog-ox", title: "The Frog and the Ox",
              text: "A little frog saw an ox drinking at the pond and envied his great size. She puffed herself up and asked her sisters if she was as big as the ox yet. They said no, so she puffed harder and harder until she burst. Do not try to be what you are not."),
        Story(id: "milkmaid-pail", title: "The Milkmaid and Her Pail",
              text: "A milkmaid carried a full pail on her head and began to dream. She would sell the milk, buy eggs, raise chickens, and buy a fine dress for the fair. Lost in the dream she tossed her head, the pail fell, and the milk was gone. Do not count your chickens before they hatch."),
        Story(id: "fox-stork", title: "The Fox and the Stork",
              text: "A fox invited a stork to dinner and served soup on a flat dish that her long bill could not use. The stork then asked the fox to dine and served supper in a tall narrow jar. The fox could only lick the rim. One bad turn deserves another."),
        Story(id: "belling-the-cat", title: "Belling the Cat",
              text: "The mice met to plan a defense against the cat. A young mouse proposed hanging a bell on her neck so all could hear her coming, and the crowd cheered. Then an old mouse rose and asked who would tie the bell on. It is easy to propose what no one can do."),
        Story(id: "two-goats", title: "The Two Goats",
              text: "Two goats met head to head in the middle of a narrow bridge above a rushing stream. Neither would back up to let the other pass, so they locked horns and pushed. Both lost their footing and tumbled into the water. Stubborn pride can cost more than a small retreat."),
        Story(id: "bear-two-travelers", title: "The Bear and the Two Travelers",
              text: "Two friends met a bear on the road. One climbed a tree at once, and the other dropped to the ground and held his breath. The bear sniffed his ear and wandered off. The man in the tree asked what the bear had said. He told me not to travel with a friend who runs at the first danger."),
        Story(id: "honest-woodcutter", title: "The Honest Woodcutter",
              text: "A woodcutter dropped his axe into a deep river and sat down in despair. A river spirit rose and offered him a golden axe, then a silver one, but he said each time that it was not his. Pleased with his honesty, the spirit gave him all three. Honesty is rewarded."),
        Story(id: "bundle-sticks", title: "The Bundle of Sticks",
              text: "An old farmer watched his sons quarrel every day. He handed them a bundle of sticks and asked each to break it, and none could. Then he untied it and they snapped the sticks one by one with ease. In unity there is strength, he said. Divided, you will break like single sticks."),
        Story(id: "miser-gold", title: "The Miser and His Gold",
              text: "A miser buried his gold in the garden and dug it up each week just to look at it. A thief watched, and one night stole the lot. The miser wailed until a neighbor said, bury a stone and visit that. Since you never meant to spend the gold, it will serve you just as well."),
        Story(id: "farmer-sons", title: "The Farmer and His Sons",
              text: "A dying farmer told his sons a treasure lay hidden in the vineyard. After he was gone they dug every foot of the ground and found no chest of gold. But the turned soil bore a harvest richer than any before. Hard work is itself the treasure."),
        Story(id: "dove-ant", title: "The Dove and the Ant",
              text: "An ant fell into a brook and was carried away by the current. A dove dropped a leaf into the water and the ant climbed aboard and drifted safely to shore. Soon after, the ant saw a hunter aiming at the dove and stung his heel. The shot went wide. One good turn earns another."),
        Story(id: "stag-pool", title: "The Stag at the Pool",
              text: "A stag admired his grand antlers in a clear pool but was ashamed of his thin legs. Hounds startled him, and those swift legs carried him far ahead. Then his antlers tangled in low branches and held him fast. What he prized had failed him, and what he scorned had nearly saved him."),
        Story(id: "fisherman-little-fish", title: "The Fisherman and the Little Fish",
              text: "A fisherman caught one small fish after a long day. The fish begged to be thrown back until it grew larger, promising to be a finer catch next year. The fisherman shook his head and kept it. A small gain in hand is worth more than a great one promised."),
        Story(id: "gnat-bull", title: "The Gnat and the Bull",
              text: "A gnat settled on the horn of a bull and rested there a long while. Before flying off he begged pardon for the burden of his weight. The bull replied that he had not even noticed him land. The smaller the mind, the greater the self importance."),
        Story(id: "fox-goat", title: "The Fox and the Goat",
              text: "A fox fell into a well and could not climb out. A thirsty goat came by, and the fox praised the sweet water until the goat jumped in. The fox climbed up over his horns and looked back down. Never trust advice from someone in trouble, he called, and look before you leap."),
        Story(id: "androcles-lion", title: "Androcles and the Lion",
              text: "A runaway slave named Androcles found a lion moaning over a thorn in its paw. He drew the thorn out and the lion became his friend. Both were later captured, and Androcles was thrown to the beasts. The same lion knew him and licked his hands. Kindness is never wasted."),
        Story(id: "peacock-crane", title: "The Peacock and the Crane",
              text: "A peacock spread his shining tail and mocked the plain gray feathers of a crane. The crane said nothing and rose into the sky, circling high above the strutting bird. Fine feathers kept the peacock on the ground. Better useful wings than idle ornament."),
        Story(id: "donkey-salt", title: "The Donkey and the Load of Salt",
              text: "A donkey hauling salt slipped in a stream, and the salt melted away, making his load light. Next trip he stumbled on purpose. His driver saw the trick and loaded him with sponges. The donkey plunged in, and rose soaked, with double the weight. The same trick rarely works twice."),
    ]
}
