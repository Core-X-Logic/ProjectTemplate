import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Users, VenetianMask } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { Can } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { listUsers } from '@/features/users/api';
import type { UserDto } from '@/features/users/types';
import { useAuth } from '@/providers/auth-provider';
import type { TenantDto } from '../types';

const PAGE_SIZE = 20;

export interface TenantUsersDialogProps {
  /** The tenant whose users are shown; `null` keeps the dialog unmounted-ish. */
  tenant: TenantDto | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Host-side "who is in this tenant?" picker with per-row impersonation — the
 * missing UI bridge over capabilities the backend already has: cross-tenant
 * user listing (`GET /api/users?tenantId=`, host-only) and cross-tenant
 * impersonation (`ImpersonateRequest.targetTenantId`).
 *
 * RBAC: the dialog opens from the tenants screen, which sits behind
 * `tenants.manage`; the listing itself needs `users.read` (backend enforced —
 * a 403 lands in the error state, not a blank grid). The per-row impersonate
 * button is additionally wrapped in `<Can permission="users.impersonate">`,
 * mirroring the backend's `@PreAuthorize` — the same triple-lock convention as
 * everywhere else.
 *
 * Cascade rule mirrored from `ImpersonateAction`: while already impersonating,
 * starting another impersonation is refused by the backend, so the button is
 * disabled instead of letting the click fail.
 *
 * An INACTIVE user cannot be impersonated (backend rule) — that row's button is
 * disabled rather than surfacing the refusal as an error toast.
 */
export function TenantUsersDialog({
  tenant,
  open,
  onOpenChange,
}: TenantUsersDialogProps) {
  const intl = useIntl();
  const auth = useAuth();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');

  // A dialog reopened for ANOTHER tenant must not carry the previous tenant's
  // page/search — that would silently show tenant B filtered by tenant A's term.
  useEffect(() => {
    setPage(0);
    setSearch('');
  }, [tenant?.id]);

  const tenantId = tenant?.id;
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['tenants', 'users', tenantId, page, search] as const,
    queryFn: () =>
      listUsers({
        tenantId: tenantId as number,
        page,
        size: PAGE_SIZE,
        search,
        sort: ['username,asc'],
      }),
    enabled: open && tenantId !== undefined,
    placeholderData: keepPreviousData,
  });

  const users = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const impersonate = async (user: UserDto) => {
    if (user.id === undefined || tenantId === undefined) {
      return;
    }
    try {
      await auth.impersonate(user.id, tenantId);
      toast.success(
        intl.formatMessage(
          { id: 'tenants.users.impersonated' },
          { username: user.username ?? '' },
        ),
      );
      onOpenChange(false);
      navigate('/');
    } catch (error) {
      toast.error(
        error instanceof ApiError && error.detail
          ? error.detail
          : intl.formatMessage({ id: 'tenants.toast.error' }),
      );
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage
              id="tenants.users.title"
              values={{ tenant: tenant?.displayName ?? tenant?.name ?? '' }}
            />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage id="tenants.users.description" />
          </DialogDescription>
        </DialogHeader>

        <Input
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
            setPage(0);
          }}
          placeholder={intl.formatMessage({
            id: 'tenants.users.searchPlaceholder',
          })}
          aria-label={intl.formatMessage({
            id: 'tenants.users.searchPlaceholder',
          })}
        />

        {isError ? (
          <DataError
            message={intl.formatMessage({ id: 'tenants.users.error' })}
            onRetry={() => refetch()}
          />
        ) : isLoading ? (
          <TableSkeleton rows={5} cols={3} />
        ) : users.length === 0 ? (
          <DataEmpty
            icon={<Users />}
            title={intl.formatMessage({ id: 'tenants.users.empty' })}
          />
        ) : (
          <ul className="flex flex-col divide-y">
            {users.map((user) => (
              <li
                key={user.id}
                className="flex items-center justify-between gap-3 py-2.5"
              >
                <div className="flex min-w-0 flex-col">
                  <span className="truncate font-medium text-foreground">
                    {user.username}
                  </span>
                  <span className="truncate text-sm text-muted-foreground">
                    {user.email}
                  </span>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Badge
                    variant={user.active ? 'success' : 'secondary'}
                    appearance="light"
                  >
                    <FormattedMessage
                      id={
                        user.active
                          ? 'tenants.badge.active'
                          : 'tenants.badge.inactive'
                      }
                    />
                  </Badge>
                  <Can permission="users.impersonate">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!user.active || auth.isImpersonating}
                      onClick={() => impersonate(user)}
                    >
                      <VenetianMask />
                      <FormattedMessage id="tenants.users.impersonate" />
                    </Button>
                  </Can>
                </div>
              </li>
            ))}
          </ul>
        )}

        {totalPages > 1 && (
          <div className="flex items-center justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((current) => current - 1)}
            >
              <FormattedMessage id="tenants.users.previous" />
            </Button>
            <span className="text-sm text-muted-foreground">
              {page + 1} / {totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              <FormattedMessage id="tenants.users.next" />
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
