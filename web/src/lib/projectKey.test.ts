import { describe, expect, it } from 'vitest';
import { suggestKey } from './projectKey';

describe('suggestKey', () => {
  it('uppercases and strips spaces/punctuation', () => {
    expect(suggestKey('My New Pro')).toBe('MYNEWPRO');
  });

  it('drops leading non-letters so the first char is a letter', () => {
    expect(suggestKey('123 Things')).toBe('THINGS');
    expect(suggestKey('  alpha')).toBe('ALPHA');
  });

  it('clamps to 10 characters', () => {
    expect(suggestKey('Alpha Beta Gamma Delta')).toBe('ALPHABETAG');
    expect(suggestKey('ABCDEFGHIJKLMNOP')).toHaveLength(10);
  });

  it('returns empty string when no letter survives', () => {
    expect(suggestKey('!!!')).toBe('');
    expect(suggestKey('123')).toBe('');
    expect(suggestKey('')).toBe('');
  });

  it('keeps digits after a leading letter', () => {
    expect(suggestKey('X1 Project')).toBe('X1PROJECT');
  });
});
