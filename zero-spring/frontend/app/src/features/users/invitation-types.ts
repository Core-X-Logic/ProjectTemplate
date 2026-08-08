/**
 * Invitation types, kept in their own module so the existing
 * `types.ts`/`hooks.ts` mock factories in sibling tests stay untouched.
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`),
 * same as the rest of the feature. They were briefly hand-declared while the
 * schema lagged the new endpoints; the typed-client-drift gate caught exactly
 * that lag on the first CI run after the merge — which is the gate doing its
 * job, and why hand copies never survive here longer than one regeneration.
 */
import type { components } from '@/api/schema';

/** Deliberately WITHOUT the token or its hash — the API never returns them. */
export type InvitationDto = components['schemas']['InvitationDto'];
export type PageInvitationDto = components['schemas']['PageInvitationDto'];
export type InviteUserRequest = components['schemas']['InviteUserRequest'];

/**
 * Derived from the schema union so a new backend status breaks compilation
 * here. Expiry is DERIVED (see {@link isExpired}), never a status.
 */
export type InvitationStatus = NonNullable<InvitationDto['status']>;

/** A PENDING invitation whose expiry has passed — re-send is the only useful action. */
export function isExpired(invitation: InvitationDto): boolean {
  return (
    invitation.status === 'PENDING' &&
    Boolean(invitation.expiresAt) &&
    new Date(invitation.expiresAt as string) < new Date()
  );
}
