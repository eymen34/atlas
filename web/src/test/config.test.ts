import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const repoWebDir = path.resolve(__dirname, '..', '..');

function readFile(rel: string): string {
  return readFileSync(path.join(repoWebDir, rel), 'utf-8');
}

describe('AC-1.1 vite.config.ts', () => {
  const raw = readFile('vite.config.ts');

  it('sets server.port to 5173', () => {
    expect(raw).toMatch(/port:\s*5173/);
  });

  it('sets server.strictPort true', () => {
    expect(raw).toMatch(/strictPort:\s*true/);
  });

  it('proxies /api to http://localhost:8080', () => {
    expect(raw).toMatch(/['"]\/api['"]\s*:\s*\{[^}]*target:\s*['"]http:\/\/localhost:8080['"]/s);
  });

  it('sets proxy ws: false explicitly', () => {
    expect(raw).toMatch(/ws:\s*false/);
  });

  it('does not proxy to any other port or target', () => {
    const targets = raw.match(/target:\s*['"][^'"]+['"]/g) ?? [];
    expect(targets).toEqual(["target: 'http://localhost:8080'"]);
  });
});

describe('AC-5.2 React Compiler absence', () => {
  const raw = readFile('vite.config.ts');

  it('does not reference the React Compiler in any form', () => {
    expect(raw).not.toMatch(/babel-plugin-react-compiler/);
    expect(raw).not.toMatch(/react-compiler/);
    expect(raw).not.toMatch(/ReactCompilerConfig/);
    expect(raw).not.toMatch(/experimental_enableCompiler/);
  });

  it('invokes react() with no babel/compiler config object', () => {
    expect(raw).toMatch(/react\(\)/);
  });
});

describe('AC-2.2 globals.css', () => {
  const raw = readFile('src/styles/globals.css');

  it('imports tailwindcss via the v4 CSS-native syntax', () => {
    expect(raw).toMatch(/@import\s+['"]tailwindcss['"]/);
  });

  it('declares shadcn CSS variables', () => {
    expect(raw).toMatch(/--background:/);
  });

  it('declares both :root and .dark blocks or @variant dark', () => {
    const hasVariant = /@variant\s+dark/.test(raw) || /@custom-variant\s+dark/.test(raw);
    const hasBlocks = /:root\s*\{/.test(raw) && /\.dark\s*\{/.test(raw);
    expect(hasVariant || hasBlocks).toBe(true);
  });

  it('does not reference tailwind.config', () => {
    expect(raw).not.toMatch(/tailwind\.config/);
  });
});
