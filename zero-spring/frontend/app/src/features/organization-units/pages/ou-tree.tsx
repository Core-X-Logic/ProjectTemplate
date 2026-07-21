import { useMemo, useState } from 'react';
import { Building2, LoaderCircle, Plus } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Can } from '@/auth/rbac';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { OuForm, OU_ROOT_VALUE, type OuFormSubmit } from '../components/ou-form';
import { OuNode, OU_MANAGE_PERMISSION } from '../components/ou-node';
import {
  useCreateOu,
  useDeleteOu,
  useMoveOu,
  useOrganizationUnits,
  useUpdateOu,
} from '../hooks';
import {
  buildOuTree,
  collectSubtreeIds,
  type OrganizationUnit,
  type OuTreeNode,
} from '../types';

/**
 * Organization Units tree screen (slice B).
 *
 * Route-level access is enforced by `<RequireAuth permission="organizationunits.manage">`
 * in `routing/routes.tsx`; node actions are additionally `<Can>`-guarded so the
 * screen stays read-only if it is ever mounted with a weaker guard.
 */

type DialogState =
  | { kind: 'create'; parentId?: number }
  | { kind: 'edit'; node: OuTreeNode }
  | { kind: 'move'; node: OuTreeNode }
  | { kind: 'delete'; node: OuTreeNode }
  | null;

interface OuMoveDialogProps {
  node: OuTreeNode;
  units: OrganizationUnit[];
  submitting: boolean;
  onSubmit: (newParentId?: number) => void;
  onCancel: () => void;
}

/** Minimal move UX: pick the new parent from a select, excluding the subtree. */
function OuMoveDialog({
  node,
  units,
  submitting,
  onSubmit,
  onCancel,
}: OuMoveDialogProps) {
  const intl = useIntl();
  const [value, setValue] = useState<string>(OU_ROOT_VALUE);

  const targets = useMemo(() => {
    const forbidden = collectSubtreeIds(units, node.id);
    return units.filter(
      (unit): unit is OrganizationUnit & { id: number } =>
        unit.id != null && !forbidden.has(unit.id),
    );
  }, [units, node.id]);

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) {
          onCancel();
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="organizationUnits.moveDialog.title" />
          </DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">
          <FormattedMessage
            id="organizationUnits.moveDialog.description"
            values={{ name: node.displayName }}
          />
        </p>
        <Select value={value} onValueChange={setValue}>
          <SelectTrigger
            aria-label={intl.formatMessage({
              id: 'organizationUnits.form.parent',
            })}
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={OU_ROOT_VALUE}>
              <FormattedMessage id="organizationUnits.form.parentRoot" />
            </SelectItem>
            {targets.map((unit) => (
              <SelectItem key={unit.id} value={String(unit.id)}>
                {unit.displayName ?? unit.code ?? String(unit.id)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={onCancel}
            disabled={submitting}
          >
            <FormattedMessage id="common.cancel" />
          </Button>
          <Button
            type="button"
            disabled={submitting}
            onClick={() =>
              onSubmit(value === OU_ROOT_VALUE ? undefined : Number(value))
            }
          >
            {submitting && <LoaderCircle className="size-4 animate-spin" />}
            <FormattedMessage id="organizationUnits.moveDialog.submit" />
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function OuTreeScreen() {
  const intl = useIntl();
  const { data, isPending, isError, refetch } = useOrganizationUnits();
  const createOu = useCreateOu();
  const updateOu = useUpdateOu();
  const moveOu = useMoveOu();
  const deleteOu = useDeleteOu();

  const [dialog, setDialog] = useState<DialogState>(null);

  const units = useMemo(() => data ?? [], [data]);
  const tree = useMemo(() => buildOuTree(units), [units]);

  const closeDialog = () => setDialog(null);

  const handleFormSubmit = async (values: OuFormSubmit) => {
    try {
      if (dialog?.kind === 'create') {
        await createOu.mutateAsync({
          displayName: values.displayName,
          parentId: values.parentId,
        });
      } else if (dialog?.kind === 'edit') {
        await updateOu.mutateAsync({
          id: dialog.node.id,
          body: { displayName: values.displayName },
        });
      }
      closeDialog();
    } catch {
      /* error toast raised by the mutation hook; keep the dialog open */
    }
  };

  const handleMove = async (newParentId?: number) => {
    if (dialog?.kind !== 'move') {
      return;
    }
    try {
      await moveOu.mutateAsync({
        id: dialog.node.id,
        body: { newParentId },
      });
      closeDialog();
    } catch {
      /* error toast raised by the mutation hook */
    }
  };

  const handleDelete = async () => {
    if (dialog?.kind !== 'delete') {
      return;
    }
    try {
      await deleteOu.mutateAsync(dialog.node.id);
    } finally {
      closeDialog();
    }
  };

  const nodeActions = {
    onAddChild: (node: OuTreeNode) =>
      setDialog({ kind: 'create', parentId: node.id }),
    onEdit: (node: OuTreeNode) => setDialog({ kind: 'edit', node }),
    onMove: (node: OuTreeNode) => setDialog({ kind: 'move', node }),
    onDelete: (node: OuTreeNode) => setDialog({ kind: 'delete', node }),
  };

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'organizationUnits.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="organizationUnits.title" />}
        description={<FormattedMessage id="organizationUnits.description" />}
        actions={
          <Can permission={OU_MANAGE_PERMISSION}>
            <Button
              type="button"
              onClick={() => setDialog({ kind: 'create' })}
            >
              <Plus className="size-4" />
              <FormattedMessage id="organizationUnits.newRoot" />
            </Button>
          </Can>
        }
      />

      <Card>
        <CardContent className="py-5">
          {isPending ? (
            <TableSkeleton rows={5} cols={1} />
          ) : isError ? (
            <DataError
              message={intl.formatMessage({
                id: 'organizationUnits.loadError',
              })}
              onRetry={() => refetch()}
              retryLabel={intl.formatMessage({ id: 'organizationUnits.retry' })}
            />
          ) : tree.length === 0 ? (
            <DataEmpty
              icon={<Building2 />}
              title={intl.formatMessage({ id: 'organizationUnits.empty' })}
              action={
                <Can permission={OU_MANAGE_PERMISSION}>
                  <Button
                    type="button"
                    onClick={() => setDialog({ kind: 'create' })}
                  >
                    <Plus className="size-4" />
                    <FormattedMessage id="organizationUnits.newRoot" />
                  </Button>
                </Can>
              }
            />
          ) : (
            <ul
              role="tree"
              aria-label={intl.formatMessage({
                id: 'organizationUnits.title',
              })}
            >
              {tree.map((node) => (
                <OuNode key={node.id} node={node} {...nodeActions} />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {(dialog?.kind === 'create' || dialog?.kind === 'edit') && (
        <Dialog
          open
          onOpenChange={(open) => {
            if (!open) {
              closeDialog();
            }
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>
                <FormattedMessage
                  id={
                    dialog.kind === 'create'
                      ? 'organizationUnits.form.createTitle'
                      : 'organizationUnits.form.editTitle'
                  }
                />
              </DialogTitle>
            </DialogHeader>
            <OuForm
              mode={dialog.kind}
              units={units}
              initialParentId={
                dialog.kind === 'create' ? dialog.parentId : undefined
              }
              initialDisplayName={
                dialog.kind === 'edit' ? dialog.node.displayName : undefined
              }
              submitting={createOu.isPending || updateOu.isPending}
              onSubmit={handleFormSubmit}
              onCancel={closeDialog}
            />
          </DialogContent>
        </Dialog>
      )}

      {dialog?.kind === 'move' && (
        <OuMoveDialog
          node={dialog.node}
          units={units}
          submitting={moveOu.isPending}
          onSubmit={handleMove}
          onCancel={closeDialog}
        />
      )}

      {dialog?.kind === 'delete' && (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) {
              closeDialog();
            }
          }}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>
                <FormattedMessage id="organizationUnits.deleteDialog.title" />
              </AlertDialogTitle>
              <AlertDialogDescription>
                <FormattedMessage
                  id="organizationUnits.deleteDialog.description"
                  values={{ name: dialog.node.displayName }}
                />
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel disabled={deleteOu.isPending}>
                <FormattedMessage id="common.cancel" />
              </AlertDialogCancel>
              <AlertDialogAction
                disabled={deleteOu.isPending}
                onClick={(event) => {
                  event.preventDefault();
                  void handleDelete();
                }}
              >
                <FormattedMessage id="organizationUnits.delete" />
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
    </div>
  );
}

/**
 * Route entry point. The organization-units catalogue is merged into the global
 * `i18n/messages/{en,tr}.ts` and served by the app-level `I18nProvider`, so no
 * feature-scoped provider is needed here.
 */
export function OuTreePage() {
  return <OuTreeScreen />;
}
