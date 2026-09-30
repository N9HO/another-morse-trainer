package app.anothermorsetrainer.morsekit

/**
 * Longer public-domain tales for the Short Stories mode, sent as a serial —
 * one short part at a time, with a bookmark that remembers where the listener
 * stopped. Like the fables, the text is abridged and plainly retold so it
 * keys cleanly: no apostrophes, quotes, or dashes; contractions expanded;
 * numbers spelled out; only sendable punctuation (. , ?).
 *
 * Translated from MorseKit/MorseDataSerials.swift — the passage texts are
 * kept byte-identical to iOS so both apps send the same serials.
 */
object MorseSerials {

    /** A long story split into short parts for continuous copy with a bookmark. */
    data class Serial(val id: String, val title: String, val author: String, val parts: List<String>)

    val all: List<Serial> = listOf(
        Serial(
            id = "speckled-band",
            title = "The Adventure of the Speckled Band",
            author = "Arthur Conan Doyle",
            parts = listOf(
                "Early one April morning a young lady in black arrived at Baker Street, shivering with fear. Her name was Helen Stoner, and she lived with her stepfather, Doctor Grimesby Roylott, the last of a ruined old family, at Stoke Moran. It is not cold that makes me shiver, she told Sherlock Holmes. It is terror.",
                "Her mother had died years before, leaving money to the doctor while the twin sisters lived with him. Roylott was a violent man who had been jailed in India, and at home he kept strange pets, a cheetah and a baboon, that roamed the grounds. He quarreled with the whole village and shut himself away.",
                "Two years ago her twin sister Julia became engaged. A fortnight before the wedding, Julia asked a strange question. Had Helen ever heard a low whistle in the dead of the night? Helen had not. Julia said it came at about three in the morning, from she knew not where.",
                "That night Helen was wakened by a wild scream. In the corridor her sister staggered from her room, swaying like a drunkard, her face blanched with terror. She shrieked, oh my God, Helen, it was the band, the speckled band. She pointed toward the doctors room, then fell, and died without another word.",
                "The police found no cause of death. The windows were barred, the walls sound, the floor solid. Julia had been quite alone, and no mark of violence was on her. What she meant by the speckled band, no one could say. Some thought she meant the gypsies camped on the lawn.",
                "Now Helen herself was to be married. Repairs had suddenly begun at the house, and she had been moved into the very room where Julia died. Last night, lying awake in that bed, she heard it, the low clear whistle in the silence of the night. At dawn she fled to Baker Street.",
                "Holmes agreed to come to Stoke Moran that afternoon. Before he left town, Doctor Roylott himself burst in, huge and furious, calling Holmes a meddler. He seized the iron poker and bent it into a curve with his great brown hands, then stalked out. Holmes laughed, and straightened the poker with one wrench.",
                "At Stoke Moran, Holmes examined the room where Julia had died and where Helen now slept. The bed was clamped to the floor and could not be moved. A dummy bell rope hung beside the pillow, connected to nothing. A small ventilator opened, not to the outside air, but into the doctors own room next door.",
                "In the doctors chamber they found a large iron safe, a saucer of milk on top of it, and a small dog lash hung on the bed post, tied into a loop. The face of Holmes grew grim. They would wait in the fatal room that night, he said, and Helen must sleep elsewhere.",
                "When the doctors light appeared, Holmes and Watson slipped into the dead womans room and waited in the dark in absolute silence. The hours crawled by. At three in the morning a light gleamed in the ventilator, and there came a soft sound of movement, then a gentle hiss, like steam from a kettle.",
                "Holmes sprang up, struck a match, and lashed furiously with his cane at the bell rope. Did you see it, Watson, he yelled. Then from the next room there rose a dreadful cry, a hoarse scream of pain and fear that froze their blood. When it died away, Holmes said quietly, it means it is all over.",
                "In the doctors room they found Roylott dead in his chair, and round his brow a strange yellow band with brown speckles, a swamp adder, the deadliest snake in India. He had sent it nightly through the ventilator to kill for money, and the snake had turned upon its master. Violence recoils upon the violent."
            )),
        Serial(
            id = "scandal-bohemia",
            title = "A Scandal in Bohemia",
            author = "Arthur Conan Doyle",
            parts = listOf(
                "To Sherlock Holmes she is always the woman. In his eyes Irene Adler eclipses the whole of her sex. One March evening a masked visitor called at Baker Street, a giant of a man in rich furs. Holmes saw through the mask at once. It was Wilhelm, the King of Bohemia, come on a matter of deep delicacy.",
                "Five years before, the King had known the adventuress Irene Adler, and there was a photograph of the two of them together. Now the King was to marry a princess of a strict northern family, and Adler had sworn to send the photograph to the brides family on the day the engagement was announced, three days hence.",
                "Hired burglars had twice ransacked her house. Her luggage had been searched, she herself waylaid. Nothing was found. The photograph stayed hidden, and the lady would not sell. Find it, said the King, whatever it costs. Holmes asked only for her address, Briony Lodge, Serpentine Avenue, St Johns Wood.",
                "Next morning Holmes went out disguised as a scruffy groom and idled among the stable men of Serpentine Avenue. He learned that the lady had one constant caller, a lawyer named Godfrey Norton. As Holmes watched the house, Norton arrived in a rush, and soon both were racing in separate cabs to the church of St Monica.",
                "Holmes followed, and found himself dragged to the altar as a witness. Irene Adler and Godfrey Norton were married before his eyes, with Holmes vouching for the groom. The bride gave him a sovereign for his trouble, and he planned to wear it on his watch chain as a souvenir of the occasion.",
                "That evening Holmes, disguised now as a kindly old clergyman, arranged a small street theater outside Briony Lodge. A scuffle broke out as the ladys carriage arrived, and the old clergyman, rushing to protect her, was struck down, bleeding at the face. The horrified lady had him carried into her sitting room to recover.",
                "Watson, waiting at the window, threw a plumbers smoke rocket into the room and raised the cry of fire. When a woman thinks her house is burning, Holmes said later, she runs at once to the thing she values most. Irene Adler ran to a sliding panel above the bell pull. The photograph was there.",
                "The alarm was declared false, and the injured clergyman slipped away. Holmes would call with the King in the morning and take the photograph from its hiding place. As he reached his own door in Baker Street, a slim youth in an ulster hurried past and wished him good night. The voice seemed oddly familiar.",
                "In the morning the King and Holmes found Briony Lodge empty. The lady and her husband had left England by the early train, never to return. In the recess was a photograph of Irene alone, and a letter addressed to Sherlock Holmes. She had pierced the disguise, dressed as a young man, and followed him home herself.",
                "She wrote that she loved a better man than the King, and that the photograph would never be used, kept only for her own safety. Holmes asked for one payment only, the photograph of Irene Adler, and he kept it always. He used to make merry over the cleverness of women, but he does so no longer."
            )),
        Serial(
            id = "red-headed-league",
            title = "The Red Headed League",
            author = "Arthur Conan Doyle",
            parts = listOf(
                "One autumn day Watson found Holmes deep in talk with a stout, florid client with blazing red hair. Jabez Wilson, a London pawnbroker, had come with a story so strange that Holmes promised Watson it was worth hearing. It began with an advertisement in the newspaper, addressed to all men with red hair.",
                "The Red Headed League, said the notice, had a vacancy worth four pounds a week for purely nominal services. Wilsons assistant, Vincent Spaulding, had urged him to apply. This Spaulding was a treasure of an assistant, working for half wages, though forever diving into the cellar to develop his photographs.",
                "Half the red headed men of London crowded Fleet Street on the appointed day, but Wilson was chosen. His duties, to sit in an office from ten to two each day and copy out the encyclopaedia by hand. He must never leave the office in those hours. For eight weeks he copied, and was paid every Saturday.",
                "Then one morning he found the door locked and a card nailed to it. The Red Headed League is dissolved. His four pounds a week were gone, and no one had ever heard of the League or its patron. Holmes laughed until he cried. Something bigger than a prank was afoot. What of this assistant, Spaulding?",
                "Wilson described him. Small, stout built, very quick, no hair on his face though he was thirty, a white splash of acid on his forehead, and pierced ears. Holmes sat up. It is enough, he said. Today is Saturday. By Monday the case would be closed. He asked Watson to meet him that night, and to bring his revolver.",
                "Holmes and Watson strolled past the shop of Wilson in Saxe Coburg Square, and Holmes thumped the pavement with his stick, then knocked and asked the assistant the way to the Strand. It was Spaulding who answered. Holmes had seen what he wanted, not the mans face, but the knees of his trousers, worn, wrinkled, and stained.",
                "Round the corner lay one of the chief banks of London, and the pieces fit. The assistant was John Clay, murderer, thief, and forger. The League had existed for one purpose only, to get the pawnbroker out of his shop for four hours every day while Clay dug a tunnel from the cellar.",
                "That night Holmes, Watson, a bank director named Merryweather, and a police agent waited in the dark vault of the bank among crates of French gold. The acid splash, the worn trouser knees, the hollow ring of the pavement, all pointed to a tunnel ending beneath their feet. They shaded the lantern and waited in blackness.",
                "After an hour a spark of light showed between the flagstones. A stone turned over, a lantern rose, and John Clay climbed lithe as a cat from the tunnel, hauling his red haired companion after him. Holmes seized him at the vault wall. The game is up, John Clay, he said. There is no use resisting.",
                "Clay bowed to them all with cool politeness and was led away. Merryweather could not thank Holmes enough, for the case had saved the bank thirty thousand pounds. It saved me from boredom, Holmes answered, yawning. Life is a struggle to escape the commonplace, and little problems like this one help."
            )),
        Serial(
            id = "gift-of-magi",
            title = "The Gift of the Magi",
            author = "O. Henry",
            parts = listOf(
                "One dollar and eighty seven cents. That was all Della had saved, and the next day would be Christmas. Sixty cents of it was in pennies, pinched one and two at a time from the grocer and the butcher. Three times she counted it. There was nothing to do but flop down on the shabby little couch and howl.",
                "Della and Jim rented a furnished flat at eight dollars a week. Two possessions were their great pride. One was the gold watch of Jim, handed down from his father and his grandfather. The other was the hair of Della, which fell about her, rippling and shining like a cascade of brown water, reaching below her knee.",
                "Della went to a shop with a sign, hair goods of all kinds. Will you buy my hair, she asked. Twenty dollars, said Madame, lifting the mass with a practiced hand. Give it to me quick, said Della. The next two hours she ransacked the stores, hunting for a present fine enough for Jim.",
                "She found it at last, a platinum fob chain for his watch, simple and true, worthy of the watch itself. Twenty one dollars they took from her. With eighty seven cents left she hurried home, and set to work with curling irons on the short close curls that now covered her head, hoping Jim would still find her pretty.",
                "Jim stepped in at seven. His eyes fixed upon Della with an expression she could not read, and it frightened her. I sold my hair, she burst out, because I could not live through Christmas without giving you a present. It will grow again. Say merry Christmas, Jim, and let us be happy.",
                "Jim drew a package from his coat. If you open that, he said gently, you will see why you had me going. Inside lay the combs, the beautiful tortoise shell combs with jeweled rims that Della had worshipped in a Broadway window, combs for the lovely vanished hair. She hugged them, and cried, and smiled, and said, my hair grows so fast, Jim.",
                "Then Della held out his present, the chain. Jim tumbled down on the couch and smiled. Della, he said, let us put our presents away and keep them a while. I sold the watch to buy your combs. Of all who give gifts, these two were the wisest. They were the magi."
            )),
        Serial(
            id = "eighty-days",
            title = "Around the World in Eighty Days",
            author = "Jules Verne",
            parts = listOf(
                "Phileas Fogg of Saville Row was an exact and solitary English gentleman whose whole life ran by the clock. He had just dismissed a servant for bringing shaving water two degrees too cold. On the second of October eighteen seventy two he engaged a cheerful Frenchman named Passepartout, who wanted nothing more than a quiet life. He had chosen the wrong master.",
                "That evening at the Reform Club the members argued over a bank robbery and the shrinking of the world. Fogg observed calmly that one could now go round the world in eighty days, and wagered twenty thousand pounds, half his fortune, that he would do it himself. The train for Dover left at eight forty five. He was on it, with Passepartout and a carpet bag.",
                "All England talked of the wager, and a detective named Fix decided that Fogg was the bank robber himself, fleeing with the stolen notes. Fix followed him to Suez and attached himself to the party, waiting everywhere for a warrant that never quite caught up. Fogg steamed on to Bombay and took the railway across India.",
                "Deep in the forest the railway simply stopped, for the line was unfinished, whatever the newspapers said. Fogg, unmoved, bought an elephant for two thousand pounds and hired a guide. Riding through the jungle, the party came upon a procession, a young widow named Aouda being led to be burned alive beside her dead husband.",
                "We have twelve hours to spare, said Fogg quietly. Let us save her. Every plan failed, until Passepartout slipped away, took the dead mans place upon the pyre, and rose from it in the smoke like a ghost. In the panic the party carried Aouda off, and she traveled on with them. Fogg lost not one day.",
                "In Hong Kong the desperate Fix drugged Passepartout in an opium den, and master and servant were separated. Fogg missed his steamer, hired a pilot boat through a storm, and reached Yokohama at last, where he found Passepartout performing in a circus of acrobats. Together again, they crossed the Pacific to San Francisco.",
                "They took the Pacific Railroad east. A herd of ten thousand buffalo stopped the train for three hours, and a rickety bridge was crossed at full speed and collapsed behind them. Then a band of Sioux attacked the train. Passepartout crept beneath the cars and uncoupled the engine, and was carried off a prisoner.",
                "Fogg took three soldiers and went back into the snow to rescue his servant, knowing the delay might cost him everything. He brought Passepartout back safe, but the train was gone. They crossed the snowy plains on a sledge under sail, caught the trains to New York, and reached the pier forty five minutes after the steamer for Liverpool had sailed.",
                "Fogg hired a small trading vessel, and when her coal ran short in mid Atlantic he bought the ship itself and burned her masts, her rails, and her decks to feed the boilers. He landed in Liverpool, and there Fix, warrant at last in hand, arrested him. The real robber had been caught three days before. Released too late, Fogg reached London five minutes late. The wager seemed lost.",
                "Ruined and calm, Fogg set his affairs in order. Aouda, whom he had saved, offered him her hand, and he accepted with joy. Passepartout, sent to book the wedding, came flying back. In crossing the world eastward they had gained a day. Fogg walked into the Reform Club at the stroke of eight forty five and won his wager. He had gained something better, a wife, and he was the happiest of men."
            ))
    )
}
