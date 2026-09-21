import Foundation

struct FollowAlongMatcher {
    let minimumCoverage: Double = 0.72

    func coverage(target: String, spoken: String) -> Double {
        let expected = normalize(target)
        let actual = normalize(spoken)
        guard !expected.isEmpty else { return 0 }
        let matched = expected.filter { actual.contains($0) }.count
        return Double(matched) / Double(expected.count)
    }

    func isComplete(target: String, spoken: String) -> Bool { coverage(target: target, spoken: spoken) >= minimumCoverage }
    private func normalize(_ text: String) -> [Character] { text.lowercased().filter { !$0.isWhitespace && !$0.isPunctuation }.map { $0 } }
}
