import { LoaderCircle, MailX, RefreshCw, Undo2 } from 'lucide-react';
import { FormattedDate, FormattedMessage, useIntl } from 'react-intl';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { DataEmpty, DataError } from '@/components/common/data-state';
import { Can } from '@/auth/rbac';
import {
  useInvitations,
  useResendInvitation,
  useRevokeInvitation,
} from '@/features/users/invitation-hooks';
import {
  isExpired,
  type InvitationDto,
} from '@/features/users/invitation-types';

interface InvitationsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function statusBadge(invitation: InvitationDto) {
  if (isExpired(invitation)) {
    return (
      <Badge variant="warning" appearance="light">
        <FormattedMessage id="users.invitations.status.expired" />
      </Badge>
    );
  }
  switch (invitation.status) {
    case 'ACCEPTED':
      return (
        <Badge variant="success" appearance="light">
          <FormattedMessage id="users.invitations.status.accepted" />
        </Badge>
      );
    case 'REVOKED':
      return (
        <Badge variant="destructive" appearance="light">
          <FormattedMessage id="users.invitations.status.revoked" />
        </Badge>
      );
    default:
      return (
        <Badge variant="secondary" appearance="light">
          <FormattedMessage id="users.invitations.status.pending" />
        </Badge>
      );
  }
}

/**
 * Pending/settled invitations of the active tenant. Re-send is the
 * recovery path for an expired token (it reissues the token and extends the
 * window); revoke closes a pending invitation for good. Both stay PENDING-only
 * server-side, so the buttons follow the same rule.
 */
export function InvitationsDialog({
  open,
  onOpenChange,
}: InvitationsDialogProps) {
  const intl = useIntl();
  // Only fetch while the dialog is actually open.
  const { data, isLoading, isError, refetch } = useInvitations(0, 50, open);
  const resend = useResendInvitation();
  const revoke = useRevokeInvitation();

  const invitations = data?.content ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="users.invitations.title" />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage id="users.invitations.description" />
          </DialogDescription>
        </DialogHeader>

        {isError ? (
          <DataError
            message={intl.formatMessage({ id: 'users.invitations.loadError' })}
            onRetry={() => refetch()}
          />
        ) : isLoading ? (
          <div className="flex items-center justify-center py-8">
            <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
          </div>
        ) : invitations.length === 0 ? (
          <DataEmpty
            icon={<MailX />}
            title={intl.formatMessage({ id: 'users.invitations.empty' })}
            description={intl.formatMessage({
              id: 'users.invitations.emptyDescription',
            })}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-start text-muted-foreground">
                  <th className="py-2 pe-4 text-start font-medium">
                    <FormattedMessage id="users.column.username" />
                  </th>
                  <th className="py-2 pe-4 text-start font-medium">
                    <FormattedMessage id="users.column.email" />
                  </th>
                  <th className="py-2 pe-4 text-start font-medium">
                    <FormattedMessage id="users.invitations.column.status" />
                  </th>
                  <th className="py-2 pe-4 text-start font-medium">
                    <FormattedMessage id="users.invitations.column.expiresAt" />
                  </th>
                  <th className="py-2 text-end font-medium">
                    <span className="sr-only">
                      <FormattedMessage id="users.column.actions" />
                    </span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {invitations.map((invitation) => (
                  <tr
                    key={invitation.id}
                    className="border-b border-border last:border-0"
                  >
                    <td className="py-2 pe-4 font-medium text-foreground">
                      {invitation.username}
                    </td>
                    <td className="py-2 pe-4">{invitation.email}</td>
                    <td className="py-2 pe-4">{statusBadge(invitation)}</td>
                    <td className="py-2 pe-4">
                      {invitation.expiresAt ? (
                        <FormattedDate
                          value={invitation.expiresAt}
                          dateStyle="medium"
                          timeStyle="short"
                        />
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="py-2 text-end">
                      {invitation.status === 'PENDING' &&
                        invitation.id !== undefined && (
                          <Can permission="users.create">
                            <div className="flex justify-end gap-1.5">
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={resend.isPending}
                                onClick={() =>
                                  resend.mutate(invitation.id as number)
                                }
                              >
                                <RefreshCw />
                                <FormattedMessage id="users.invitations.action.resend" />
                              </Button>
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={revoke.isPending}
                                onClick={() =>
                                  revoke.mutate(invitation.id as number)
                                }
                              >
                                <Undo2 />
                                <FormattedMessage id="users.invitations.action.revoke" />
                              </Button>
                            </div>
                          </Can>
                        )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
