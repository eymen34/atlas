import { mergeAttributes } from '@tiptap/core';
import Mention from '@tiptap/extension-mention';
import type { SuggestionOptions } from '@tiptap/suggestion';
import type { Member } from '@/api/projects';

/**
 * Shared TipTap Mention node (T-022, D4 client side). Used by BOTH the composer
 * and the read-only item so an authored mention round-trips identically.
 *
 * <p>SECURITY: only {@code data-id} and {@code data-label} are parsed and
 * rendered. Any other attribute on a stored {@code <span>} (onclick, onerror,
 * style, href, …) is dropped on parse and never emitted on render — TipTap parses
 * through the schema, so this is the read-side XSS guard (never
 * dangerouslySetInnerHTML). The SERVER remains the authority for which mentions
 * actually resolve.
 */
export const mentionExtension = Mention.extend({
  addAttributes() {
    return {
      id: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-id'),
        renderHTML: (attributes) =>
          attributes.id ? { 'data-id': String(attributes.id) } : {},
      },
      label: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-label'),
        renderHTML: (attributes) =>
          attributes.label ? { 'data-label': String(attributes.label) } : {},
      },
    };
  },
  parseHTML() {
    return [{ tag: 'span[data-id]' }];
  },
  renderHTML({ node, HTMLAttributes }) {
    const label = (node.attrs.label as string | null) ?? (node.attrs.id as string | null) ?? '';
    return ['span', mergeAttributes({ class: 'mention' }, HTMLAttributes), `@${label}`];
  },
}).configure({ HTMLAttributes: { class: 'mention rounded px-1 text-primary' } });

interface MentionItem {
  id: string;
  label: string;
}

/**
 * Composer-only autocomplete: filters project members by mention-handle prefix and
 * inserts a mention node on select. Implemented with a plain DOM popup (no extra
 * dependency); the SERVER still re-derives the canonical mention set on submit, so
 * this is purely an authoring convenience.
 */
export function createMentionSuggestion(members: Member[]): Omit<SuggestionOptions, 'editor'> {
  return {
    items: ({ query }): MentionItem[] =>
      members
        .filter((m) => m.mentionHandle && m.mentionHandle.toLowerCase().startsWith(query.toLowerCase()))
        .slice(0, 8)
        .map((m) => ({ id: m.userId, label: m.mentionHandle })),
    render: () => {
      let box: HTMLDivElement | null = null;
      let active = 0;
      let current: MentionItem[] = [];
      let cmd: ((item: MentionItem) => void) | null = null;

      const paint = () => {
        if (!box) return;
        box.innerHTML = '';
        current.forEach((item, i) => {
          const row = document.createElement('button');
          row.type = 'button';
          row.textContent = `@${item.label}`;
          row.className =
            'block w-full px-2 py-1 text-left text-sm ' +
            (i === active ? 'bg-accent text-accent-foreground' : '');
          row.addEventListener('mousedown', (e) => {
            e.preventDefault();
            cmd?.(item);
          });
          box!.appendChild(row);
        });
      };

      const place = (rect: (() => DOMRect | null) | null | undefined) => {
        if (!box || !rect) return;
        const r = rect();
        if (!r) return;
        box.style.position = 'fixed';
        box.style.left = `${r.left}px`;
        box.style.top = `${r.bottom + 4}px`;
      };

      return {
        onStart: (props) => {
          current = props.items as MentionItem[];
          cmd = (item) => props.command(item);
          active = 0;
          box = document.createElement('div');
          box.className =
            'z-50 min-w-40 overflow-hidden rounded-md border bg-popover p-1 shadow-md';
          document.body.appendChild(box);
          paint();
          place(props.clientRect);
        },
        onUpdate: (props) => {
          current = props.items as MentionItem[];
          cmd = (item) => props.command(item);
          active = 0;
          paint();
          place(props.clientRect);
        },
        onKeyDown: (props) => {
          if (props.event.key === 'ArrowDown') {
            active = (active + 1) % Math.max(1, current.length);
            paint();
            return true;
          }
          if (props.event.key === 'ArrowUp') {
            active = (active - 1 + current.length) % Math.max(1, current.length);
            paint();
            return true;
          }
          if (props.event.key === 'Enter' && current[active]) {
            cmd?.(current[active]);
            return true;
          }
          if (props.event.key === 'Escape') {
            box?.remove();
            box = null;
            return true;
          }
          return false;
        },
        onExit: () => {
          box?.remove();
          box = null;
        },
      };
    },
  };
}
