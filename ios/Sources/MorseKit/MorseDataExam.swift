import Foundation

// Reference data for the ARRL/FCC-style code exam mode: the rig / antenna /
// weather / power / temperature / age vocabulary the generator draws on, plus a
// bundled library of ready-made QSO-style passages the learner can pick instead
// of a freshly generated one. The bundled passages are original text authored in
// the same on-air ragchew style as W1AW / QST code-practice transmissions (no
// QST text is copied verbatim). All values use only sendable Morse characters.
extension MorseData {

    // MARK: Exam vocabulary pools

    /// Common HF transceivers (no internal spaces, so each is a single copy token).
    public static let rigs: [String] = [
        "IC7300", "FT991", "K3", "TS590", "FT817", "KX3",
        "IC7610", "FTDX10", "TS890", "FT710", "IC705", "K4"
    ]

    /// Transmit power levels heard on the air.
    public static let powers: [String] = [
        "5W", "10W", "50W", "100W", "200W", "500W", "1KW"
    ]

    /// Antenna types.
    public static let antennas: [String] = [
        "DIPOLE", "VERTICAL", "YAGI", "ENDFED", "LOOP", "G5RV",
        "BEAM", "LONGWIRE", "HEXBEAM", "WINDOM", "INVL", "MOXON"
    ]

    /// One-word weather descriptions (the ragchew "WX" field).
    public static let weathers: [String] = [
        "SUNNY", "CLOUDY", "RAINY", "SNOWY", "CLEAR", "WINDY",
        "FOGGY", "WARM", "COLD", "HOT", "MILD", "STORMY"
    ]

    /// Temperatures (F or C), each a single token.
    public static let temps: [String] = [
        "32F", "45F", "55F", "60F", "72F", "85F", "90F",
        "0C", "10C", "15C", "20C", "28C"
    ]

    /// Operator ages.
    public static let ages: [String] = [
        "19", "25", "34", "41", "45", "52", "60", "63", "71", "78"
    ]

    // MARK: Bundled exam passages

    /// A bundled passage paired with the speed it was authored for.
    public struct ExamSample: Identifiable, Sendable {
        public let id: String
        public let speed: ExamSpeed
        public let passage: ExamPassage
        public init(id: String, speed: ExamSpeed, passage: ExamPassage) {
            self.id = id
            self.speed = speed
            self.passage = passage
        }
    }

    /// A small library of ready-made exam passages — a couple at each license
    /// speed — built with the same template the generator uses, so the questions
    /// generated from them line up with the text.
    public static let examSamples: [ExamSample] = [
        ExamSample(id: "novice-w1aw", speed: .novice5,
                   passage: ExamPassage(toCall: "W1AW", deCall: "K9LA", name: "JIM",
                                        qth: "IL", rst: "599", rig: "IC7300",
                                        power: "100W", antenna: "DIPOLE",
                                        weather: "SUNNY", temp: "72F", age: "45")),
        ExamSample(id: "novice-n0ax", speed: .novice5,
                   passage: ExamPassage(toCall: "N0AX", deCall: "W7PHX", name: "DAVE",
                                        qth: "AZ", rst: "579", rig: "KX3",
                                        power: "5W", antenna: "ENDFED",
                                        weather: "CLEAR", temp: "85F", age: "34")),
        ExamSample(id: "general-k3lr", speed: .general13,
                   passage: ExamPassage(toCall: "K3LR", deCall: "AA3B", name: "BOB",
                                        qth: "PA", rst: "589", rig: "K3",
                                        power: "500W", antenna: "YAGI",
                                        weather: "WINDY", temp: "55F", age: "52")),
        ExamSample(id: "general-w6oat", speed: .general13,
                   passage: ExamPassage(toCall: "W6OAT", deCall: "N5XJ", name: "STEVE",
                                        qth: "CA", rst: "559", rig: "FTDX10",
                                        power: "100W", antenna: "VERTICAL",
                                        weather: "FOGGY", temp: "60F", age: "63")),
        ExamSample(id: "extra-k1ttt", speed: .extra20,
                   passage: ExamPassage(toCall: "K1TTT", deCall: "W5KFT", name: "TOM",
                                        qth: "TX", rst: "599", rig: "IC7610",
                                        power: "1KW", antenna: "HEXBEAM",
                                        weather: "HOT", temp: "90F", age: "41")),
        ExamSample(id: "extra-n2ic", speed: .extra20,
                   passage: ExamPassage(toCall: "N2IC", deCall: "K0XYZ", name: "MIKE",
                                        qth: "CO", rst: "569", rig: "K4",
                                        power: "200W", antenna: "BEAM",
                                        weather: "SNOWY", temp: "20C", age: "60")),
    ]

    /// Bundled passages authored for a given speed.
    public static func examSamples(for speed: ExamSpeed) -> [ExamSample] {
        examSamples.filter { $0.speed == speed }
    }
}
