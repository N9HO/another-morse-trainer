import Foundation

/// A built-in news feed the Short Stories mode can pull headlines from, so the
/// learner decodes real, unpredictable text — the modern version of W1AW
/// sending text from QST. Feeds are plain public RSS; each source lists a
/// couple of candidate URLs tried in order so a path change on the publisher's
/// side degrades gracefully instead of breaking the mode.
enum NewsSource: String, Codable, CaseIterable, Identifiable {
    case hamRadio, world, topStories, technology

    var id: String { rawValue }

    /// Label for the setup-screen picker.
    var label: String {
        switch self {
        case .hamRadio:   return "Ham radio news"
        case .world:      return "World news (BBC)"
        case .topStories: return "Top stories (NPR)"
        case .technology: return "Technology (NPR)"
        }
    }

    /// Attribution shown on the story card.
    var attribution: String {
        switch self {
        case .hamRadio:   return "Amateur Radio Daily"
        case .world:      return "BBC World News"
        case .topStories: return "NPR Top Stories"
        case .technology: return "NPR Technology"
        }
    }

    /// Candidate feed URLs, tried in order until one parses.
    var feedURLs: [URL] {
        let strings: [String]
        switch self {
        case .hamRadio:
            strings = ["https://daily.hamweekly.com/rss/",
                       "https://daily.hamweekly.com/feed/",
                       "https://daily.hamweekly.com/index.xml",
                       "https://daily.hamweekly.com/rss.xml"]
        case .world:
            strings = ["https://feeds.bbci.co.uk/news/world/rss.xml"]
        case .topStories:
            strings = ["https://feeds.npr.org/1001/rss.xml"]
        case .technology:
            strings = ["https://feeds.npr.org/1019/rss.xml"]
        }
        return strings.compactMap(URL.init(string:))
    }
}

/// Downloads a news source's feed and hands back its headlines. Results are
/// cached (most recent successful fetch per source) so a dead radio — er,
/// network — still leaves something to decode.
///
/// `Sendable` for real: the only stored state is the `URLSession`, which is
/// itself `Sendable`, and the cache lives in `UserDefaults`. The fetch
/// completion is typed `@MainActor` because that is where it is delivered,
/// and `@Sendable` because it is carried there from the session's callback
/// thread.
final class NewsFetcher: Sendable {

    /// One feed entry, still in plain English (sanitizing to sendable Morse
    /// text is the caller's job so display and keying stay in one place).
    struct Item: Codable, Equatable {
        let title: String
        let summary: String
    }

    enum FetchError: Error {
        case network(String)
        case emptyFeed

        var message: String {
            switch self {
            case .network:   return "Could not reach the news feed."
            case .emptyFeed: return "The feed came back empty."
            }
        }
    }

    /// How many headlines a session keeps from a feed.
    static let maxItems = 12

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        session = URLSession(configuration: config)
    }

    /// Fetch `source`, trying its candidate URLs in order. Calls `completion`
    /// on the main queue with the parsed items or the last error.
    func fetch(_ source: NewsSource,
               completion: @escaping @MainActor @Sendable (Result<[Item], FetchError>) -> Void) {
        attempt(urls: source.feedURLs, source: source, completion: completion)
    }

    private func attempt(urls: [URL],
                         source: NewsSource,
                         completion: @escaping @MainActor @Sendable (Result<[Item], FetchError>) -> Void) {
        guard let url = urls.first else {
            DispatchQueue.main.async { completion(.failure(.network("no feed URL"))) }
            return
        }
        let task = session.dataTask(with: url) { [weak self] data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if let data, error == nil, (200...299).contains(status) {
                let items = RSSFeedParser.parse(data: data, limit: NewsFetcher.maxItems)
                if !items.isEmpty {
                    self?.store(items, for: source)
                    DispatchQueue.main.async { completion(.success(items)) }
                    return
                }
            }
            if urls.count > 1 {
                self?.attempt(urls: Array(urls.dropFirst()), source: source,
                              completion: completion)
            } else {
                let failure: FetchError = error != nil
                    ? .network(error!.localizedDescription)
                    : .emptyFeed
                DispatchQueue.main.async { completion(.failure(failure)) }
            }
        }
        task.resume()
    }

    // MARK: - Last-good cache

    private static func cacheKey(_ source: NewsSource) -> String {
        "MorseTrainer.newsCache.\(source.rawValue)"
    }

    private struct Cached: Codable {
        let fetchedAt: Date
        let items: [Item]
    }

    private func store(_ items: [Item], for source: NewsSource) {
        let cached = Cached(fetchedAt: Date(), items: items)
        if let data = try? JSONEncoder().encode(cached) {
            UserDefaults.standard.set(data, forKey: Self.cacheKey(source))
        }
    }

    /// The most recent successful fetch for `source`, if any.
    func cached(_ source: NewsSource) -> (items: [Item], fetchedAt: Date)? {
        guard let data = UserDefaults.standard.data(forKey: Self.cacheKey(source)),
              let cached = try? JSONDecoder().decode(Cached.self, from: data),
              !cached.items.isEmpty else { return nil }
        return (cached.items, cached.fetchedAt)
    }
}

/// Minimal RSS 2.0 / Atom parser: collects each item's title and
/// description/summary and ignores everything else. Tolerant of CDATA and of
/// feeds that put HTML in the description (stripping happens downstream).
private final class RSSFeedParser: NSObject, XMLParserDelegate {

    static func parse(data: Data, limit: Int) -> [NewsFetcher.Item] {
        let delegate = RSSFeedParser(limit: limit)
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        // Items collected before a mid-stream error still count.
        return delegate.items
    }

    private let limit: Int
    private var items: [NewsFetcher.Item] = []

    private var inItem = false
    private var currentTitle = ""
    private var currentSummary = ""
    private var currentElement = ""

    private init(limit: Int) {
        self.limit = limit
    }

    private static let itemElements: Set<String> = ["item", "entry"]
    private static let titleElements: Set<String> = ["title"]
    private static let summaryElements: Set<String> = ["description", "summary"]

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]) {
        let name = elementName.lowercased()
        if Self.itemElements.contains(name) {
            inItem = true
            currentTitle = ""
            currentSummary = ""
        }
        currentElement = name
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        appendContent(string)
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        appendContent(String(data: CDATABlock, encoding: .utf8) ?? "")
    }

    private func appendContent(_ string: String) {
        guard inItem else { return }
        if Self.titleElements.contains(currentElement) {
            currentTitle += string
        } else if Self.summaryElements.contains(currentElement) {
            currentSummary += string
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        let name = elementName.lowercased()
        if Self.itemElements.contains(name) {
            inItem = false
            let title = currentTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            let summary = currentSummary.trimmingCharacters(in: .whitespacesAndNewlines)
            if !title.isEmpty, items.count < limit {
                items.append(NewsFetcher.Item(title: title, summary: summary))
            }
            if items.count >= limit { parser.abortParsing() }
        }
        currentElement = ""
    }
}
