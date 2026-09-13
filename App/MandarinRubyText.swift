import SwiftUI
import MuralCore

struct MandarinRubyText: View {
    let tokens: [MandarinPronunciationToken]
    var body: some View {
        WrappingRubyLayout(spacing: 4, rowSpacing: 8) {
            ForEach(Array(tokens.enumerated()), id: \.offset) { _, token in
                if let pinyin = token.pinyin {
                    VStack(spacing: 2) {
                        Text(token.text)
                            .font(.system(.title2, design: .rounded, weight: .medium))
                        Text(pinyin)
                            .font(.system(.caption2, design: .rounded, weight: .medium))
                            .foregroundStyle(MuralColor.secondary)
                    }
                    .fixedSize()
                } else {
                    Text(token.text)
                        .font(.system(.title2, design: .rounded, weight: .medium))
                        .padding(.bottom, 15)
                        .fixedSize()
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct WrappingRubyLayout: Layout {
    var spacing: CGFloat
    var rowSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(proposal: proposal, subviews: subviews)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in rows(proposal: ProposedViewSize(width: bounds.width, height: bounds.height), subviews: subviews) {
            var x = bounds.midX - row.width / 2
            for item in row.items {
                subviews[item.index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(item.size))
                x += item.size.width + spacing
            }
        }
    }

    private func rows(proposal: ProposedViewSize, subviews: Subviews) -> [Row] {
        let maxWidth = proposal.width ?? .infinity
        var rows: [Row] = []
        var current = Row(y: 0)
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let nextWidth = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if nextWidth > maxWidth, !current.items.isEmpty {
                rows.append(current)
                current = Row(y: current.y + current.height + rowSpacing)
            }
            current.items.append(Item(index: index, size: size))
            current.width = current.items.count == 1 ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
        }
        if !current.items.isEmpty { rows.append(current) }
        return rows
    }

    private struct Row {
        var y: CGFloat
        var width: CGFloat = 0
        var height: CGFloat = 0
        var items: [Item] = []
    }

    private struct Item {
        var index: Int
        var size: CGSize
    }
}
