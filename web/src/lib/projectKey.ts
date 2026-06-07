/**
 * Derives a candidate project key from a display name, matching the backend
 * pattern {@code ^[A-Z][A-Z0-9]{1,9}$}: uppercase, keep only A–Z/0–9, drop any
 * leading non-letters so the first character is a letter, then clamp to 10
 * characters. Returns '' when no letter survives (the user must type a key).
 *
 * Examples: "My New Project" → "MYNEWPROJE"; "123 Things" → "THINGS";
 * "!!!" → "" ; "Alpha Beta Gamma Delta" → "ALPHABETAG".
 */
export function suggestKey(name: string): string {
  const alnum = name.toUpperCase().replace(/[^A-Z0-9]/g, '');
  const fromLetter = alnum.replace(/^[^A-Z]+/, '');
  return fromLetter.slice(0, 10);
}
