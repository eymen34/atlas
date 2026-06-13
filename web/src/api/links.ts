import { type CreateLinkRequest, type LinkResponse, LinksService } from './generated';

/**
 * T-026 app-facing ticket-link API, mirroring tickets.ts / attachments.ts.
 *
 * {@link LinkRelation} is the LITERAL union (template-literal over the generated enum)
 * so it tracks codegen. Only three relations are user-facing on create.
 */
export type LinkRelation = `${NonNullable<LinkResponse['relation']>}`;
export type UserFacingRelation = 'BLOCKS' | 'DUPLICATES' | 'RELATES_TO';
export type LinkTargetStatus = `${NonNullable<LinkResponse['targetStatus']>}`;

export interface TicketLink {
  id: string;
  fromTicketId: string;
  toTicketId: string;
  relation: LinkRelation;
  targetTicketKey: string;
  targetTitle: string;
  targetStatus: LinkTargetStatus;
  targetDeleted: boolean;
  createdBy: string;
  createdAt: string;
}

/** Maps a generated LinkResponse; throws on a malformed row (toTicket precedent). */
export function toLink(r: LinkResponse): TicketLink {
  if (!r.id) {
    throw new Error('Malformed link response: missing id');
  }
  return {
    id: r.id,
    fromTicketId: r.fromTicketId ?? '',
    toTicketId: r.toTicketId ?? '',
    relation: (r.relation ? String(r.relation) : 'RELATES_TO') as LinkRelation,
    targetTicketKey: r.targetTicketKey ?? '',
    targetTitle: r.targetTitle ?? '',
    targetStatus: (r.targetStatus ? String(r.targetStatus) : 'TODO') as LinkTargetStatus,
    targetDeleted: r.targetDeleted ?? false,
    createdBy: r.createdBy ?? '',
    createdAt: r.createdAt ?? '',
  };
}

/** TanStack Query keys — one list per ticket. */
export const linkKeys = {
  all: ['links'] as const,
  list: (ticketId: string) => [...linkKeys.all, ticketId] as const,
};

export async function listTicketLinks(ticketId: string): Promise<TicketLink[]> {
  return (await LinksService.listTicketLinks(ticketId)).map(toLink);
}

export async function createTicketLink(
  ticketId: string,
  toTicketKey: string,
  relation: UserFacingRelation
): Promise<TicketLink> {
  return toLink(
    await LinksService.createTicketLink(ticketId, {
      toTicketKey,
      relation: relation as CreateLinkRequest['relation'],
    })
  );
}

export async function deleteTicketLink(linkId: string): Promise<void> {
  await LinksService.deleteTicketLink(linkId);
}
