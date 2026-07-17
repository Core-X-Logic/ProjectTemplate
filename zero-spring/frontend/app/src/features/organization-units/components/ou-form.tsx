import { useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { DialogFooter } from '@/components/ui/dialog';
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { OrganizationUnit } from '../types';

/** Sentinel select value for "no parent" (radix forbids empty item values). */
export const OU_ROOT_VALUE = 'root';

export interface OuFormValues {
  displayName: string;
  parentId: string;
}

export interface OuFormSubmit {
  displayName: string;
  parentId?: number;
}

interface OuFormProps {
  mode: 'create' | 'edit';
  /** Flat unit list used to offer parent choices (create mode only). */
  units: OrganizationUnit[];
  /** Preselected parent (`add child` entry point); `undefined` = root. */
  initialParentId?: number;
  /** Current display name when editing. */
  initialDisplayName?: string;
  submitting?: boolean;
  onSubmit: (values: OuFormSubmit) => void;
  onCancel: () => void;
}

/**
 * Create/edit form (react-hook-form + zod).
 *
 * `parentId` is only editable on create — the backend exposes re-parenting
 * exclusively through the dedicated move endpoint, and edit only mutates
 * `displayName` (`UpdateOuRequest`).
 */
export function OuForm({
  mode,
  units,
  initialParentId,
  initialDisplayName,
  submitting = false,
  onSubmit,
  onCancel,
}: OuFormProps) {
  const intl = useIntl();

  const schema = useMemo(
    () =>
      z.object({
        displayName: z
          .string()
          .trim()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        parentId: z.string(),
      }),
    [intl],
  );

  const form = useForm<OuFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      displayName: initialDisplayName ?? '',
      parentId:
        initialParentId != null ? String(initialParentId) : OU_ROOT_VALUE,
    },
  });

  const submit = form.handleSubmit((values) => {
    onSubmit({
      displayName: values.displayName.trim(),
      parentId:
        values.parentId === OU_ROOT_VALUE
          ? undefined
          : Number(values.parentId),
    });
  });

  const parentOptions = useMemo(
    () =>
      units.filter(
        (unit): unit is OrganizationUnit & { id: number } => unit.id != null,
      ),
    [units],
  );

  return (
    <Form {...form}>
      <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
        <FormField
          control={form.control}
          name="displayName"
          render={({ field }) => (
            <FormItem>
              <FormLabel>
                <FormattedMessage id="organizationUnits.form.displayName" />
              </FormLabel>
              <FormControl>
                <Input
                  {...field}
                  autoComplete="off"
                  placeholder={intl.formatMessage({
                    id: 'organizationUnits.form.displayNamePlaceholder',
                  })}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {mode === 'create' && (
          <FormField
            control={form.control}
            name="parentId"
            render={({ field }) => (
              <FormItem>
                <FormLabel>
                  <FormattedMessage id="organizationUnits.form.parent" />
                </FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    <SelectItem value={OU_ROOT_VALUE}>
                      <FormattedMessage id="organizationUnits.form.parentRoot" />
                    </SelectItem>
                    {parentOptions.map((unit) => (
                      <SelectItem key={unit.id} value={String(unit.id)}>
                        {unit.displayName ?? unit.code ?? String(unit.id)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        )}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={onCancel}
            disabled={submitting}
          >
            <FormattedMessage id="common.cancel" />
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting && <LoaderCircle className="size-4 animate-spin" />}
            <FormattedMessage
              id={mode === 'create' ? 'common.create' : 'common.save'}
            />
          </Button>
        </DialogFooter>
      </form>
    </Form>
  );
}
