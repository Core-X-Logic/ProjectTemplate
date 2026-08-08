/**
 * Invitation types, kept in their own module so the existing
 * `types.ts`/`hooks.ts` mock factories in sibling tests stay untouched.
 *
 * TODO(gen:api): these DTO shapes are HAND-DECLARED because the generated
 * schema (`npm run gen:api`, needs the backend up in dev profile) has not been
 * regenerated with the invitation endpoints yet. As soon as it is, replace
 * every declaration below with aliases from `components['schemas']`
 * (InvitationDto, PageInvitationDto, InviteUserRequest) — a hand copy is
 * exactly the kind of thing that lets a backend field rename slip through the
 * compiler. The rest of the feature already works alias-first; this module
 * must follow the moment the schema catches up.
 */

/** Mirrors backend `InvitationStatus`; expiry is DERIVED (see {@link isExpired}), never a status. */
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED';

/** Mirrors backend `InvitationDto` — deliberately WITHOUT the token or its hash. */
export interface InvitationDto {
  id?: number;
  username?: string;
  email?: string;
  roleNames?: string[];
  status?: InvitationStatus;
  /** ISO instant. */
  expiresAt?: string;
  /** ISO instant. */
  createdAt?: string;
}

/** Mirrors backend `InviteUserRequest` (`users.create`; the invitee only ever picks a password). */
export interface InviteUserRequest {
  username: string;
  email: string;
  roleNames?: string[];
}

/** Spring `Page<InvitationDto>` — only the fields the screens actually read. */
export interface PageInvitationDto {
  content?: InvitationDto[];
  totalElements?: number;
  totalPages?: number;
  number?: number;
  size?: number;
}

/** A PENDING invitation whose expiry has passed — re-send is the only useful action. */
export function isExpired(invitation: InvitationDto): boolean {
  return (
    invitation.status === 'PENDING' &&
    Boolean(invitation.expiresAt) &&
    new Date(invitation.expiresAt as string) < new Date()
  );
}
