import { useEffect, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { useSaveBillingCredentials } from '../hooks';
import {
  credentialStatus,
  PROVIDER_FIELDS,
  providerLabel,
  type ProviderStatusDto,
} from '../types';

export interface ProviderCredentialsDialogProps {
  /** Provider row being edited; `null` keeps the dialog closed. */
  credential: ProviderStatusDto | null;
  onOpenChange: (open: boolean) => void;
}

/**
 * Write-only credentials dialog.
 *
 * Security posture (deliberate, do not "improve"):
 *  - Inputs are `type="password"` and are NEVER prefilled — the backend only
 *    ever returns a masked hint, and this dialog resets to empty on every
 *    open, so a raw value can never round-trip through the DOM.
 *  - When credentials are already stored (`source === 'db'`), the masked hint
 *    is shown as the placeholder and an empty field means "keep the current
 *    value" — empty fields are OMITTED from the `credentials` map, never sent
 *    as `""`. Fields listed in `configuredFields` carry a "set" badge so the
 *    operator can see which values already exist without ever seeing them.
 *  - First-time saves (env-only or unconfigured) require every field, because
 *    there is no stored row to merge into.
 */
export function ProviderCredentialsDialog({
  credential,
  onOpenChange,
}: ProviderCredentialsDialogProps) {
  const intl = useIntl();
  const saveCredentials = useSaveBillingCredentials();

  const provider = credential?.provider ?? '';
  const fields = PROVIDER_FIELDS[provider] ?? [];
  const configuredFields = credential?.configuredFields ?? [];
  const stored =
    credential !== null && credentialStatus(credential) === 'stored';
  const open = credential !== null && fields.length > 0;

  // Cross-field error ("enter at least one field") — plain state, not RHF,
  // because it concerns the form as a whole, not a single input.
  const [formError, setFormError] = useState<string | null>(null);

  const form = useForm<Record<string, string>>({ defaultValues: {} });
  const { reset } = form;

  // Every open starts from a blank slate: nothing typed on a previous visit
  // (nor any stored secret — we never had it) may appear in the inputs.
  useEffect(() => {
    if (credential) {
      reset(
        Object.fromEntries(
          (PROVIDER_FIELDS[credential.provider ?? ''] ?? []).map((field) => [
            field.name,
            '',
          ]),
        ),
      );
      setFormError(null);
    }
  }, [credential, reset]);

  const submit = form.handleSubmit(async (values) => {
    if (!credential?.provider) {
      return;
    }
    setFormError(null);

    // Empty fields are omitted: "leave empty = do not change" (stored rows).
    const credentials = Object.fromEntries(
      fields
        .map((field) => [field.name, (values[field.name] ?? '').trim()])
        .filter(([, value]) => value !== ''),
    );

    if (!stored && Object.keys(credentials).length < fields.length) {
      setFormError(
        intl.formatMessage({ id: 'billingProviders.dialog.requiredAll' }),
      );
      return;
    }
    if (Object.keys(credentials).length === 0) {
      setFormError(
        intl.formatMessage({ id: 'billingProviders.dialog.atLeastOne' }),
      );
      return;
    }

    try {
      await saveCredentials.mutateAsync({
        provider: credential.provider,
        body: { credentials },
      });
      // Success: close. The invalidation triggered by the hook refetches the
      // list, so the card's masked hint updates to the newly stored value.
      onOpenChange(false);
    } catch {
      // The mutation hook already toasted the ProblemDetail; keep the dialog
      // open so the operator can correct and retry.
    }
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage
              id="billingProviders.dialog.title"
              values={{ name: providerLabel(provider) }}
            />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage id="billingProviders.dialog.description" />
            {stored ? (
              <>
                {' '}
                <FormattedMessage id="billingProviders.dialog.updateHint" />
              </>
            ) : null}
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
            {fields.map((fieldConfig) => (
              <FormField
                key={fieldConfig.name}
                control={form.control}
                name={fieldConfig.name}
                defaultValue=""
                render={({ field }) => (
                  <FormItem>
                    {/* The badge sits NEXT TO the label (not inside it) so it
                        does not pollute the input's accessible name. */}
                    <div className="flex items-center gap-2">
                      <FormLabel>
                        <FormattedMessage id={fieldConfig.labelId} />
                      </FormLabel>
                      {configuredFields.includes(fieldConfig.name) ? (
                        <Badge variant="success" appearance="light">
                          <FormattedMessage id="billingProviders.field.configuredBadge" />
                        </Badge>
                      ) : null}
                    </div>
                    <FormControl>
                      <Input
                        {...field}
                        type="password"
                        autoComplete="off"
                        // The masked hint is the only trace of an existing
                        // value; the input itself always starts (and stays)
                        // empty until the operator types a replacement.
                        placeholder={
                          stored
                            ? (credential?.maskedHint ?? '••••')
                            : undefined
                        }
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ))}

            {formError ? (
              <p role="alert" className="text-sm text-destructive">
                {formError}
              </p>
            ) : null}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
              >
                <FormattedMessage id="billingProviders.dialog.cancel" />
              </Button>
              <Button type="submit" disabled={saveCredentials.isPending}>
                {saveCredentials.isPending ? (
                  <>
                    <LoaderCircle className="animate-spin" />
                    <FormattedMessage id="billingProviders.dialog.saving" />
                  </>
                ) : (
                  <FormattedMessage id="billingProviders.dialog.submit" />
                )}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
