import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { useQuery } from '@tanstack/react-query';
import { create } from 'zustand';
import { useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { DayPicker } from 'react-day-picker';
import { Command } from 'cmdk';

interface PackageJson {
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
}

const pkgPath = path.resolve(__dirname, '..', '..', 'package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf-8')) as PackageJson;

describe('AC-5.1 frontend_state and ui_library libraries are present and exact-pinned', () => {
  it.each([
    ['@tanstack/react-query', useQuery],
    ['zustand', create],
    ['@tiptap/react', useEditor],
    ['@tiptap/starter-kit', StarterKit],
    ['react-day-picker', DayPicker],
    ['cmdk', Command],
  ])('imports %s as a value', (_name, value) => {
    expect(value).toBeDefined();
  });

  const REQUIRED = [
    '@tanstack/react-query',
    'zustand',
    '@tiptap/react',
    '@tiptap/starter-kit',
    'react-day-picker',
    'cmdk',
  ];

  it.each(REQUIRED)('declares %s in dependencies with an exact pin (no ^ or ~)', (name) => {
    const version = pkg.dependencies[name];
    expect(version, `${name} must be present in dependencies`).toBeDefined();
    expect(version, `${name} must be exact-pinned, got "${version}"`).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
