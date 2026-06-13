import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SnippetText } from '../SnippetText';

describe('SnippetText', () => {
  it('wraps [[ ]] sentinels in <strong> and leaves the rest as plain text', () => {
    const { container } = render(<SnippetText snippet="alpha [[beta]] gamma [[delta]]" />);

    const strongs = container.querySelectorAll('strong');
    expect(strongs).toHaveLength(2);
    expect(strongs[0]).toHaveTextContent('beta');
    expect(strongs[1]).toHaveTextContent('delta');
    // Sentinels are stripped; the surrounding text is preserved verbatim.
    expect(container.textContent).toBe('alpha beta gamma delta');
  });

  it('never injects markup — script/img-like content renders as inert text (no dangerouslySetInnerHTML)', () => {
    const { container } = render(
      <SnippetText snippet="[[<script>alert(1)</script>]] and <img src=x onerror=hack()>" />
    );

    // No real elements are created from the dangerous strings…
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    // …they survive as escaped text content instead.
    expect(container.textContent).toContain('<script>alert(1)</script>');
    expect(container.textContent).toContain('<img src=x onerror=hack()>');
    // The highlighted fragment is still a <strong> carrying the literal text.
    expect(container.querySelector('strong')).toHaveTextContent('<script>alert(1)</script>');
  });

  it.each([null, undefined, ''])('renders nothing for %p without throwing', (value) => {
    const { container } = render(<SnippetText snippet={value} />);
    expect(container).toBeEmptyDOMElement();
  });
});
