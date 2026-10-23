/**
 * Chat answers as text (docs/plans/phase-7.md §5.2, §13): an answer is never rendered as HTML or markdown. Only two
 * things are done to it, by string handling: emphasis markers are stripped, and the `[n]` citation markers are found
 * so they can become links. Line breaks and list markers stay as written.
 */

/**
 * A pair of emphasis markers around text, at word edges: `**March 2026**`, `*note*`, `***both***`, `_note_`,
 * `__note__`. The opening marker follows a start or a non-word character and comes before a non-space; the closing
 * one, the same marker, comes after a non-space and before an end or a non-word character. So a lone marker stays (`SQ *TNK 0193`), as does one
 * inside a word (`snake_case`) and a list marker (`* item`, followed by a space).
 */
const EMPHASIS = /(?<![\w*_])(\*\*\*|\*\*|__|\*|_)(?=[^\s*_])(.+?)(?<=[^\s*_])\1(?![\w*_])/g

/** The answer with emphasis markers removed, everything else as written. */
export function stripEmphasis(text: string): string {
  return text
    .split('\n')
    .map((line) => {
      // Repeated, so nested emphasis (`**a *b* c**`) loses every layer.
      let previous: string
      let current = line
      do {
        previous = current
        current = current.replace(EMPHASIS, '$2')
      } while (current !== previous)
      return current
    })
    .join('\n')
}

export type AnswerPart = { text: string } | { marker: number }

/**
 * The answer split into text and citation markers. A marker becomes a part only when `cited` has that number, so a
 * `[7]` the answer wrote with no citation 7 stays text; nothing else in the text is ever a link.
 */
export function answerParts(text: string, cited: ReadonlySet<number>): AnswerPart[] {
  const parts: AnswerPart[] = []
  let last = 0
  for (const match of text.matchAll(/\[(\d{1,3})\]/g)) {
    const marker = Number(match[1])
    if (!cited.has(marker)) {
      continue
    }
    if (match.index > last) {
      parts.push({ text: text.slice(last, match.index) })
    }
    parts.push({ marker })
    last = match.index + match[0].length
  }
  if (last < text.length) {
    parts.push({ text: text.slice(last) })
  }
  return parts
}
