import SwiftUI

/// A compact number + ham-punctuation row pinned above the software keyboard for
/// the typed practice modes (Type It, Code Exam, QSO Simulator). Digits and the
/// common prosign characters are then one tap away instead of buried behind the
/// keyboard's number page — the same idea as the Carrier Wave logger's accessory
/// row, done the SwiftUI-native way with a `.keyboard` toolbar group.
struct MorseKeyboardRow: ViewModifier {
    @Binding var text: String
    /// Dismiss the keyboard (clear the field's focus). Called by the chevron.
    var onDone: () -> Void

    /// Digits first (the common case), then the punctuation that shows up in
    /// callsigns, Q-codes, and exchanges.
    private static let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                               "/", "?", ".", ",", "="]

    func body(content: Content) -> some View {
        content.toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Self.keys, id: \.self) { key in
                            Button {
                                text.append(key)
                            } label: {
                                Text(key)
                                    .font(.system(.body, design: .monospaced))
                                    .frame(minWidth: 32, minHeight: 34)
                                    .foregroundStyle(.white)
                                    .background(Theme.navyRaised,
                                                in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                            }
                            .accessibilityLabel("Type \(key)")
                        }
                    }
                    .padding(.vertical, 2)
                }
                Spacer()
                Button(action: onDone) {
                    Image(systemName: "keyboard.chevron.compact.down")
                }
                .accessibilityLabel("Dismiss keyboard")
            }
        }
    }
}

extension View {
    /// Attach the Morse number/punctuation accessory row to a focused text field.
    func morseKeyboardRow(text: Binding<String>, onDone: @escaping () -> Void) -> some View {
        modifier(MorseKeyboardRow(text: text, onDone: onDone))
    }
}
