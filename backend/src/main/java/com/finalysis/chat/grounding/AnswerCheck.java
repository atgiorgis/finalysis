package com.finalysis.chat.grounding;

import java.util.List;

/**
 * One answer, checked (docs/plans/phase-6.md §5, §6): its plain text with only issued markers, every figure found, what
 * each matched and which refs each one cites, and the figures that matched nothing.
 *
 * @param text      the text that may be shown: links and URLs removed, unknown markers removed, issued ones kept as
 *                  refs ({@code [t3]}) until citations renumber them
 * @param markers   the markers kept, in order
 * @param dropped   marker refs removed because this turn never issued them ({@code citationsDropped})
 * @param removed   links, images, tags and URLs removed
 * @param matches   every figure in order, with the values it matched and the ones it cites
 * @param ungrounded the figures that matched nothing, in order
 */
public record AnswerCheck(String text, List<CitationMarkers.Marker> markers, int dropped, int removed,
                          List<FigureMatch> matches, List<Figure> ungrounded) {

    /**
     * A figure, every value it matched, and the values it cites: those under the refs the model marked in the same
     * sentence; else all of them when they share one ref, or when the figure is exact money or an exact percent
     * (unlikely to be a coincidence); else none (a whole number or a rounded figure under several refs).
     */
    public record FigureMatch(Figure figure, List<GroundedSet.Value> values, List<GroundedSet.Value> cited) {

        public FigureMatch {
            values = List.copyOf(values);
            cited = List.copyOf(cited);
        }
    }

    public AnswerCheck {
        markers = List.copyOf(markers);
        matches = List.copyOf(matches);
        ungrounded = List.copyOf(ungrounded);
    }

    public boolean grounded() {
        return ungrounded.isEmpty();
    }

    /** {@code GROUNDED} or {@code NO_FIGURES} when every figure matched; null when the answer can't be shown. */
    public GroundingStatus status() {
        if (!grounded()) {
            return null;
        }
        return matches.isEmpty() ? GroundingStatus.NO_FIGURES : GroundingStatus.GROUNDED;
    }

    public int figuresChecked() {
        return matches.size();
    }
}
