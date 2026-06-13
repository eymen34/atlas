import { useVirtualizer } from '@tanstack/react-virtual';
import { type ReactNode, useRef } from 'react';
import type { Ticket } from '@/api/tickets';

/**
 * Windowed card list, mounted by {@link BoardColumn} ONLY past VIRTUALIZE_THRESHOLD
 * cards (D4 — flagged YAGNI). The scroll container is the measured element; rows are
 * absolutely positioned. DndContext's MeasuringStrategy.Always keeps droppable rects
 * fresh while scrolling mid-drag (E3).
 */
export function VirtualizedCardList({
  items,
  renderItem,
}: {
  items: Ticket[];
  renderItem: (ticket: Ticket) => ReactNode;
}) {
  const parentRef = useRef<HTMLDivElement>(null);
  // TanStack Virtual's useVirtualizer is a well-established hook; the v7 plugin's
  // experimental incompatible-library heuristic false-flags it (it returns
  // non-memoizable functions, harmless here — the column re-renders on data change).
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 88,
    overscan: 8,
  });

  return (
    <div ref={parentRef} className="max-h-[70vh] overflow-y-auto">
      <div style={{ height: virtualizer.getTotalSize(), position: 'relative', width: '100%' }}>
        {virtualizer.getVirtualItems().map((row) => (
          <div
            key={row.key}
            data-index={row.index}
            ref={virtualizer.measureElement}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${row.start}px)`,
              paddingBottom: 8,
            }}
          >
            {renderItem(items[row.index])}
          </div>
        ))}
      </div>
    </div>
  );
}
