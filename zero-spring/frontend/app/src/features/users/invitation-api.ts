import { apiFetch } from '@/api/client';
import type {
  InvitationDto,
  InviteUserRequest,
  PageInvitationDto,
} from '@/features/users/invitation-types';

/**
 * Endpoint wrappers for the invitation flow. Every admin verb below is
 * `users.create` on the backend — an invitation is a deferred user creation.
 * The DTOs never carry the token: it exists only inside the invitation e-mail.
 */

const INVITATIONS_BASE = '/api/invitations';

/** `GET /api/invitations` — server-side pagination (`users.create`). */
export function listInvitations(
  page = 0,
  size = 50,
): Promise<PageInvitationDto> {
  return apiFetch<PageInvitationDto>(
    `${INVITATIONS_BASE}?page=${page}&size=${size}&sort=id,desc`,
  );
}

/** `POST /api/invitations` (`users.create`) — mails the single-use token. */
export function inviteUser(body: InviteUserRequest): Promise<InvitationDto> {
  return apiFetch<InvitationDto>(INVITATIONS_BASE, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * `POST /api/invitations/{id}/resend` (`users.create`) — reissues the token
 * (the previous one stops working) and extends the validity window.
 */
export function resendInvitation(id: number): Promise<InvitationDto> {
  return apiFetch<InvitationDto>(`${INVITATIONS_BASE}/${id}/resend`, {
    method: 'POST',
  });
}

/** `POST /api/invitations/{id}/revoke` (`users.create`). */
export function revokeInvitation(id: number): Promise<InvitationDto> {
  return apiFetch<InvitationDto>(`${INVITATIONS_BASE}/${id}/revoke`, {
    method: 'POST',
  });
}
