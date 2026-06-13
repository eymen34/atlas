import { Fragment } from 'react';

/**
 * Renders a ts_headline snippet (T-028) whose highlighted fragments are wrapped in
 * {@code [[ ]]} sentinels (set in the repo's ts_headline call) as &lt;strong&gt; nodes.
 *
 * Highlighting is done by SPLITTING the string into React text nodes — NEVER via
 * {@code dangerouslySetInnerHTML} — so server-derived ticket text (including anything that
 * looks like markup) can never inject DOM. Null / undefined / empty input renders nothing.
 */
interface Segment {
  text: string;
  highlight: boolean;
}

function toSegments(snippet: string): Segment[] {
  // Fresh regex per call: a module-level /g regex carries lastIndex between calls.
  const sentinel = /\[\[(.+?)\]\]/g;
  const segments: Segment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = sentinel.exec(snippet)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ text: snippet.slice(lastIndex, match.index), highlight: false });
    }
    segments.push({ text: match[1], highlight: true });
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < snippet.length) {
    segments.push({ text: snippet.slice(lastIndex), highlight: false });
  }
  return segments;
}

export function SnippetText({ snippet }: { snippet?: string | null }) {
  if (!snippet) {
    return null;
  }
  return (
    <span data-testid="search-snippet" className="text-sm text-muted-foreground">
      {toSegments(snippet).map((segment, i) =>
        segment.highlight ? (
          <strong key={i} className="font-semibold text-foreground">
            {segment.text}
          </strong>
        ) : (
          <Fragment key={i}>{segment.text}</Fragment>
        )
      )}
    </span>
  );
}
