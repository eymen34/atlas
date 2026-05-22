import { readFileSync } from 'node:fs';
import path from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

const html = readFileSync(path.resolve(__dirname, '..', '..', 'index.html'), 'utf-8');
const dom = new JSDOM(html);
const doc = dom.window.document;

describe('SEC-1 / EC-9 index.html is CSP-safe', () => {
  it('contains no inline script bodies', () => {
    const inlineScripts = Array.from(doc.querySelectorAll('script')).filter(
      (s) => (s.textContent ?? '').trim().length > 0
    );
    expect(inlineScripts).toHaveLength(0);
  });

  it('contains no <style> blocks with non-whitespace text', () => {
    const inlineStyles = Array.from(doc.querySelectorAll('style')).filter(
      (s) => (s.textContent ?? '').trim().length > 0
    );
    expect(inlineStyles).toHaveLength(0);
  });

  it('uses no inline style attributes', () => {
    const withStyle = doc.querySelectorAll('[style]');
    expect(withStyle.length).toBe(0);
  });

  it('uses no inline event-handler attributes (on*)', () => {
    const offenders: string[] = [];
    const walker = doc.createTreeWalker(doc, dom.window.NodeFilter.SHOW_ELEMENT);
    let node: Node | null = walker.nextNode();
    while (node) {
      const el = node as Element;
      for (const attr of Array.from(el.attributes)) {
        if (/^on[a-z]+$/i.test(attr.name)) {
          offenders.push(`${el.tagName}:${attr.name}`);
        }
      }
      node = walker.nextNode();
    }
    expect(offenders).toEqual([]);
  });

  it('uses no javascript: URIs in href/src/action', () => {
    const offenders: string[] = [];
    for (const attr of ['href', 'src', 'action']) {
      for (const el of Array.from(doc.querySelectorAll(`[${attr}]`))) {
        if ((el.getAttribute(attr) ?? '').toLowerCase().startsWith('javascript:')) {
          offenders.push(`${el.tagName}:${attr}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
