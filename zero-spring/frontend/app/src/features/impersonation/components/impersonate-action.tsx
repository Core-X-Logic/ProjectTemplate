import { ApiError } from '@/api/client';
import { Can } from '@/auth/rbac';
import { VenetianMask } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuth } from '@/providers/auth-provider';
import { DropdownMenuItem } from '@/components/ui/dropdown-menu';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { useImpersonationMessages } from '../messages';

interface ImpersonateActionProps {
  /** Target user to impersonate. */
  userId: number;
  /** Target username — only used to personalise the success toast. */
  username?: string;
  /** Optional tenant to impersonate the user in (cross-tenant host case). */
  tenantId?: number;
}

/**
 * Row-level "Impersonate" action, guarded by `users.impersonate`.
 *
 * Rendered inside the users-list row action `DropdownMenu`, so it is a real
 * `DropdownMenuItem` (role=menuitem) — consistent with the other row actions —
 * and acts on `onSelect` (the menu closes itself on a successful select).
 *
 * Cascade rule (UI mirror of the backend block): while already inside an
 * impersonation session (`auth.isImpersonating`) the item is marked
 * `aria-disabled`, its `onSelect` is a no-op that keeps the menu open, and a
 * tooltip explains why — you must return to your own account before starting a
 * new impersonation. `aria-disabled` (rather than a hard-disabled, pointer
 * events-off item) keeps the item focusable so the tooltip stays reachable.
 *
 * Renders `null` for users without `users.impersonate`.
 */
export function ImpersonateAction({
  userId,
  username,
  tenantId,
}: ImpersonateActionProps) {
  const auth = useAuth();
  const navigate = useNavigate();
  const t = useImpersonationMessages();

  const blocked = auth.isImpersonating;

  const handleSelect = async (event: Event) => {
    if (blocked) {
      // No-op while impersonating; keep the menu open so the reason stays read.
      event.preventDefault();
      return;
    }
    try {
      await auth.impersonate(userId, tenantId);
      toast.success(t('impersonation.success', { username: username ?? '' }));
      navigate('/');
    } catch (error) {
      toast.error(t('impersonation.error'), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    }
  };

  return (
    <Can permission="users.impersonate">
      <Tooltip>
        <TooltipTrigger asChild>
          <DropdownMenuItem
            aria-disabled={blocked || undefined}
            onSelect={handleSelect}
            className={blocked ? 'opacity-50 cursor-not-allowed' : undefined}
          >
            <VenetianMask />
            {t('impersonation.action.label')}
          </DropdownMenuItem>
        </TooltipTrigger>
        {blocked && (
          <TooltipContent>
            {t('impersonation.action.cascadeBlocked')}
          </TooltipContent>
        )}
      </Tooltip>
    </Can>
  );
}
