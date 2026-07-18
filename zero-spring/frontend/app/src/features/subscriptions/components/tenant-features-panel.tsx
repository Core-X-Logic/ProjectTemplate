import { useEffect, useMemo, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Can } from '@/auth/rbac';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { featureValueType, isFeatureTrue } from '@/features/editions/types';
import { useTenantFeatures, useUpdateTenantFeatures } from '../hooks';
import type { TenantFeatureDto } from '../types';

/**
 * Tenant feature-override editor (CONTRACT-phase5.md §A.2).
 *
 * `GET /api/tenant-features/{tenantId}` returns the whole resolution chain per
 * feature (`overrideValue` → `editionValue` → `defaultValue`), so the panel can
 * edit ONLY the override while showing what an empty field falls back to.
 *
 * The input kind comes from the feature definition's `type` (carried on the
 * DTO): BOOLEAN → switch, NUMBER → numeric input, anything else → text.
 *
 * Save is dirty-only — `PUT` receives just the rows the operator touched.
 */

export interface TenantFeaturesPanelProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Tenant whose overrides are edited; the panel idles until it is set. */
  tenantId: number | null;
  /** Shown in the dialog description. */
  tenantName?: string;
  /** Permission gating the save button (`tenantfeatures.manage`). */
  managePermission?: string;
}

/** The value a cleared override falls back to (edition, then default). */
function inheritedValue(feature: TenantFeatureDto): string {
  return feature.editionValue ?? feature.defaultValue ?? '';
}

/** Seeds the draft from the OVERRIDE column only — not the resolved value. */
function toDraft(features: TenantFeatureDto[]): Record<string, string> {
  return features.reduce<Record<string, string>>((acc, feature) => {
    if (feature.name) {
      acc[feature.name] = feature.overrideValue ?? '';
    }
    return acc;
  }, {});
}

interface TenantFeatureRowProps {
  feature: TenantFeatureDto;
  value: string;
  onChange: (next: string) => void;
}

function TenantFeatureRow({ feature, value, onChange }: TenantFeatureRowProps) {
  const name = feature.name ?? '';
  const type = featureValueType(feature.type);
  const inputId = `tenant-feature-${name}`;
  const inherited = inheritedValue(feature);

  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={inputId}>{name}</Label>

      {type === 'BOOLEAN' ? (
        <Switch
          id={inputId}
          checked={isFeatureTrue(value !== '' ? value : inherited)}
          onCheckedChange={(checked) => onChange(checked ? 'true' : 'false')}
        />
      ) : (
        <Input
          id={inputId}
          type={type === 'NUMBER' ? 'number' : 'text'}
          inputMode={type === 'NUMBER' ? 'numeric' : undefined}
          value={value}
          autoComplete="off"
          placeholder={inherited || undefined}
          onChange={(event) => onChange(event.target.value)}
        />
      )}

      {inherited ? (
        <p className="text-xs text-muted-foreground">
          <FormattedMessage
            id="subscriptions.features.inherited"
            values={{ value: inherited }}
          />
        </p>
      ) : null}
    </div>
  );
}

export function TenantFeaturesPanel({
  open,
  onOpenChange,
  tenantId,
  tenantName,
  managePermission = 'tenantfeatures.manage',
}: TenantFeaturesPanelProps) {
  const intl = useIntl();

  const {
    data: features,
    isLoading,
    isError,
  } = useTenantFeatures(open && tenantId !== null ? tenantId : undefined);
  const updateFeatures = useUpdateTenantFeatures();

  const [draft, setDraft] = useState<Record<string, string>>({});
  const [baseline, setBaseline] = useState<Record<string, string>>({});

  // Re-seed on every fresh snapshot (open, tenant switch, post-save refetch).
  useEffect(() => {
    const seeded = toDraft(features ?? []);
    setDraft(seeded);
    setBaseline(seeded);
  }, [features]);

  const dirtyNames = useMemo(
    () => Object.keys(draft).filter((name) => draft[name] !== baseline[name]),
    [draft, baseline],
  );

  const save = () => {
    if (tenantId === null || dirtyNames.length === 0) {
      return;
    }
    updateFeatures.mutate({
      tenantId,
      values: dirtyNames.map((name) => ({ name, value: draft[name] })),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="subscriptions.features.title" />
          </DialogTitle>
          <DialogDescription>
            {tenantName
              ? `${tenantName} — ${intl.formatMessage({ id: 'subscriptions.features.description' })}`
              : intl.formatMessage({ id: 'subscriptions.features.description' })}
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="flex flex-col gap-3 py-2">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-2/3" />
          </div>
        ) : isError ? (
          <p role="alert" className="py-2 text-sm text-destructive">
            <FormattedMessage id="subscriptions.features.error" />
          </p>
        ) : !features || features.length === 0 ? (
          <p className="py-2 text-sm text-muted-foreground">
            <FormattedMessage id="subscriptions.features.empty" />
          </p>
        ) : (
          <div className="flex max-h-96 flex-col gap-4 overflow-y-auto py-2">
            {features.map((feature) => (
              <TenantFeatureRow
                key={feature.name}
                feature={feature}
                value={draft[feature.name ?? ''] ?? ''}
                onChange={(next) =>
                  setDraft((current) => ({
                    ...current,
                    [feature.name ?? '']: next,
                  }))
                }
              />
            ))}
          </div>
        )}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            <FormattedMessage id="subscriptions.features.close" />
          </Button>
          <Can permission={managePermission}>
            <Button
              type="button"
              onClick={save}
              disabled={updateFeatures.isPending || dirtyNames.length === 0}
            >
              {updateFeatures.isPending && (
                <LoaderCircle className="size-4 animate-spin" />
              )}
              <FormattedMessage
                id={
                  updateFeatures.isPending
                    ? 'subscriptions.features.saving'
                    : 'subscriptions.features.save'
                }
              />
            </Button>
          </Can>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
