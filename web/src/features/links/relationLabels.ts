import type { LinkRelation, UserFacingRelation } from '@/api/links';

/** Display label per stored relation type (T-026). */
export const RELATION_LABELS: Record<LinkRelation, string> = {
  BLOCKS: 'Blocks',
  IS_BLOCKED_BY: 'Blocked by',
  DUPLICATES: 'Duplicates',
  IS_DUPLICATED_BY: 'Duplicated by',
  RELATES_TO: 'Relates to',
};

/** Group render order on the ticket detail page. */
export const ORDERED_RELATIONS: LinkRelation[] = [
  'BLOCKS',
  'IS_BLOCKED_BY',
  'DUPLICATES',
  'IS_DUPLICATED_BY',
  'RELATES_TO',
];

/** The three relations a user may pick when creating a link. */
export const USER_FACING_RELATIONS: UserFacingRelation[] = ['BLOCKS', 'DUPLICATES', 'RELATES_TO'];
