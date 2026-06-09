import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const uiDir = path.resolve(__dirname, '..', 'components', 'ui');

// AC-3.1 / post-implementation note: the canonical ticket list is
// [button, input, label, card, dialog, dropdown-menu, tooltip, toast,
//  tabs, select, textarea, avatar, badge, separator, scroll-area, command]
// but `toast` was substituted with `sonner` (shadcn 4.x deprecated `toast`)
// per the documented architecture amendment (toast_library = sonner).
// T-020 added `table` for the ticket-list view.
const CANONICAL_SHADCN_FILES = [
  'button.tsx',
  'input.tsx',
  'label.tsx',
  'card.tsx',
  'dialog.tsx',
  'dropdown-menu.tsx',
  'tooltip.tsx',
  'sonner.tsx',
  'tabs.tsx',
  'select.tsx',
  'textarea.tsx',
  'avatar.tsx',
  'badge.tsx',
  'separator.tsx',
  'scroll-area.tsx',
  'command.tsx',
  'table.tsx',
] as const;

describe('AC-3.1 shadcn UI component scaffold', () => {
  it('contains exactly 17 .tsx files in src/components/ui/', () => {
    const tsx = readdirSync(uiDir).filter((f) => f.endsWith('.tsx'));
    expect(tsx).toHaveLength(17);
  });

  for (const filename of CANONICAL_SHADCN_FILES) {
    it(`includes ${filename}`, () => {
      expect(existsSync(path.join(uiDir, filename))).toBe(true);
    });
  }

  it('exposes at least one named export per component module', async () => {
    for (const filename of CANONICAL_SHADCN_FILES) {
      const moduleId = `@/components/ui/${filename.replace(/\.tsx$/, '')}`;
      const mod = (await import(moduleId)) as Record<string, unknown>;
      const namedExports = Object.keys(mod).filter((k) => k !== 'default');
      expect(namedExports.length, `${filename} should export at least one named export`).toBeGreaterThan(0);
    }
  });
});
