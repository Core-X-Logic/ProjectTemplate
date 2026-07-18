import { useEffect, useMemo, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Can } from '@/auth/rbac';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { useFeatureDefinitions, useSetEditionFeatures } from '../hooks';
import {
  featureValueType,
  isFeatureTrue,
  type FeatureDefinitionDto,
  type FeatureValueDto,
} from '../types';

/**
 * Feature-value editor for a single edition (CONTRACT-phase5.md §A.2).
 *
 * The registry (`GET /api/features/definitions`) drives BOTH the rows and the
 * input kind: BOOLEAN → switch, NUMBER → numeric input, anything else → text.
 * The definition's `defaultValue` is shown as placeholder + hint, so an empty
 * field visibly means "inherit the default" rather than "blank".
 *
 * Save is dirty-only: `PUT /api/editions/{id}/features` receives just the rows
 * the operator actually touched (batch), matching the settings screen's
 * behaviour.
 */

export interface FeatureValuesEditorProps {
  /** Edition being edited; the editor is read-only until one exists. */
  editionId: number;
  /** Current values from `EditionDetailDto.features`. */
  values: FeatureValueDto[];
  /** Permission gating the save button (`editions.manage`). */
  managePermission?: string;
}

/** `name → value` map for quick lookup against the definition list. */
function toValueMap(values: FeatureValueDto[]): Record<string, string> {
  return values.reduce<Record<string, string>>((acc, item) => {
    if (item.name) {
      acc[item.name] = item.value ?? '';
    }
    return acc;
  }, {});
}

/** Seeds the draft from the definitions, falling back to the edition value. */
function toDraft(
  definitions: FeatureDefinitionDto[],
  values: FeatureValueDto[],
): Record<string, string> {
  const assigned = toValueMap(values);
  return definitions.reduce<Record<string, string>>((acc, definition) => {
    const name = definition.name;
    if (name) {
      acc[name] = assigned[name] ?? '';
    }
    return acc;
  }, {});
}

interface FeatureRowProps {
  definition: FeatureDefinitionDto;
  value: string;
  onChange: (next: string) => void;
}

/** One definition row — the input kind is derived from `definition.type`. */
function FeatureRow({ definition, value, onChange }: FeatureRowProps) {
  const intl = useIntl();

  const name = definition.name ?? '';
  const type = featureValueType(definition.type);
  const inputId = `feature-${name}`;
  // `displayNameKey` is a localization key; fall back to the raw name when the
  // catalogue has no entry for it (unknown/vendor features still render).
  const label = definition.displayNameKey
    ? intl.formatMessage({
        id: definition.displayNameKey,
        defaultMessage: name,
      })
    : name;
  const defaultValue = definition.defaultValue ?? '';

  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={inputId}>{label}</Label>

      {type === 'BOOLEAN' ? (
        <Switch
          id={inputId}
          checked={isFeatureTrue(value !== '' ? value : defaultValue)}
          onCheckedChange={(checked) => onChange(checked ? 'true' : 'false')}
        />
      ) : (
        <Input
          id={inputId}
          type={type === 'NUMBER' ? 'number' : 'text'}
          inputMode={type === 'NUMBER' ? 'numeric' : undefined}
          value={value}
          autoComplete="off"
          placeholder={defaultValue || undefined}
          onChange={(event) => onChange(event.target.value)}
        />
      )}

      {defaultValue ? (
        <p className="text-xs text-muted-foreground">
          <FormattedMessage
            id="editions.features.default"
            values={{ value: defaultValue }}
          />
        </p>
      ) : null}
    </div>
  );
}

export function FeatureValuesEditor({
  editionId,
  values,
  managePermission = 'editions.manage',
}: FeatureValuesEditorProps) {
  const {
    data: definitions,
    isLoading,
    isError,
  } = useFeatureDefinitions();
  const setFeatures = useSetEditionFeatures();

  const [draft, setDraft] = useState<Record<string, string>>({});
  const [baseline, setBaseline] = useState<Record<string, string>>({});

  // Re-seed whenever a refetch delivers a fresh snapshot (e.g. after Save), so
  // the dirty comparison is always against what the server currently holds.
  useEffect(() => {
    const seeded = toDraft(definitions ?? [], values);
    setDraft(seeded);
    setBaseline(seeded);
  }, [definitions, values]);

  const dirtyNames = useMemo(
    () => Object.keys(draft).filter((name) => draft[name] !== baseline[name]),
    [draft, baseline],
  );

  if (isLoading) {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-2/3" />
      </div>
    );
  }

  if (isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        <FormattedMessage id="editions.features.error" />
      </p>
    );
  }

  if (!definitions || definitions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        <FormattedMessage id="editions.features.empty" />
      </p>
    );
  }

  const save = () => {
    if (dirtyNames.length === 0) {
      return;
    }
    setFeatures.mutate({
      id: editionId,
      values: dirtyNames.map((name) => ({ name, value: draft[name] })),
    });
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-4 sm:grid-cols-2">
        {definitions.map((definition) => (
          <FeatureRow
            key={definition.name}
            definition={definition}
            value={draft[definition.name ?? ''] ?? ''}
            onChange={(next) =>
              setDraft((current) => ({
                ...current,
                [definition.name ?? '']: next,
              }))
            }
          />
        ))}
      </div>

      <div className="flex justify-end">
        <Can permission={managePermission}>
          <Button
            type="button"
            onClick={save}
            disabled={setFeatures.isPending || dirtyNames.length === 0}
          >
            {setFeatures.isPending && (
              <LoaderCircle className="size-4 animate-spin" />
            )}
            <FormattedMessage
              id={
                setFeatures.isPending
                  ? 'editions.features.saving'
                  : 'editions.features.save'
              }
            />
          </Button>
        </Can>
      </div>
    </div>
  );
}
