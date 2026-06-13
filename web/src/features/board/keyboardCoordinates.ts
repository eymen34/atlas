import type { ClientRect, KeyboardCoordinateGetter } from '@dnd-kit/core';
import type { TicketStatus } from '@/api/tickets';
import { COLUMN_ORDER } from './statusOrder';

const ARROWS = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'];
const CARD_STEP = 96; // approx card height + gap for within-column vertical moves

/**
 * KeyboardSensor coordinate getter for the board (T-027, D3/AC3). Left/Right jump the
 * dragged card to the CENTER of the adjacent column (one keypress = one column),
 * Up/Down nudge within the column. Reads fresh `droppableRects` (kept current by
 * DndContext's MeasuringStrategy.Always — E3) so column targets are accurate mid-drag.
 */
export const boardCoordinateGetter: KeyboardCoordinateGetter = (event, { currentCoordinates, context }) => {
  if (!ARROWS.includes(event.code)) {
    return undefined;
  }
  event.preventDefault();

  const collisionRect = context.collisionRect;
  if (!collisionRect) {
    return currentCoordinates;
  }

  if (event.code === 'ArrowUp' || event.code === 'ArrowDown') {
    const dy = event.code === 'ArrowDown' ? CARD_STEP : -CARD_STEP;
    return { x: currentCoordinates.x, y: currentCoordinates.y + dy };
  }

  // Left/Right: shift x by the gap between the current and adjacent column centers.
  const columns = COLUMN_ORDER.map((status) => ({
    status,
    rect: context.droppableRects.get(status),
  })).filter((c): c is { status: TicketStatus; rect: ClientRect } => Boolean(c.rect));
  if (columns.length === 0) {
    return currentCoordinates;
  }

  const currentCenterX = collisionRect.left + collisionRect.width / 2;
  let currentIdx = 0;
  let bestDist = Number.POSITIVE_INFINITY;
  columns.forEach((c, i) => {
    const dist = Math.abs(c.rect.left + c.rect.width / 2 - currentCenterX);
    if (dist < bestDist) {
      bestDist = dist;
      currentIdx = i;
    }
  });

  const nextIdx =
    event.code === 'ArrowRight'
      ? Math.min(currentIdx + 1, columns.length - 1)
      : Math.max(currentIdx - 1, 0);
  const targetCenterX = columns[nextIdx].rect.left + columns[nextIdx].rect.width / 2;
  return { x: currentCoordinates.x + (targetCenterX - currentCenterX), y: currentCoordinates.y };
};
