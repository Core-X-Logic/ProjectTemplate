import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { Check, Copy, LoaderCircle, TriangleAlert } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import {
  Alert,
  AlertContent,
  AlertDescription,
  AlertIcon,
  AlertTitle,
} from '@/components/ui/alert';
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
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import { z } from 'zod';
import { useCreateTenant } from '../hooks';
import { TENANT_NAME_PATTERN } from '../types';

interface CreateTenantFormValues {
  name: string;
  displayName: string;
  adminEmail: string;
  adminPassword: string;
}

export interface CreateTenantDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Tenant creation dialog over `POST /api/tenants`.
 *
 * The backend (20247d5, closing Issue #1) creates the tenant TOGETHER with its
 * bootstrap `admin` user, so `adminEmail` is required. `adminPassword` is
 * optional: left empty, the server generates a strong credential and returns
 * it exactly once as `generatedAdminPassword` — the dialog then switches to a
 * one-time reveal (monospace + copy) instead of closing. The password lives
 * only in this component's state and is discarded when the dialog reopens;
 * it is never persisted or logged.
 *
 * `name` is validated against the backend's `@Pattern` locally; a duplicate
 * name is a 409 that only the server can detect, and its ProblemDetail `detail`
 * reaches the operator through the mutation's error toast. The admin password,
 * when supplied, is checked server-side against the settings-driven password
 * policy — a policy violation likewise surfaces through the error toast.
 */
export function CreateTenantDialog({
  open,
  onOpenChange,
}: CreateTenantDialogProps) {
  const intl = useIntl();
  const createTenant = useCreateTenant();
  const { isCopied, copyToClipboard } = useCopyToClipboard();

  // One-time reveal of the server-generated admin password. Deliberately plain
  // component state: nothing else may ever hold this value.
  const [generatedPassword, setGeneratedPassword] = useState<string | null>(
    null,
  );

  const schema = useMemo(
    () =>
      z.object({
        name: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' }))
          .regex(
            TENANT_NAME_PATTERN,
            intl.formatMessage({ id: 'tenants.create.namePattern' }),
          ),
        displayName: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        adminEmail: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' }))
          .refine(
            (value) => z.email().safeParse(value).success,
            intl.formatMessage({ id: 'tenants.create.adminEmailInvalid' }),
          ),
        // Optional; the server-side password policy is the authority here.
        adminPassword: z.string(),
      }),
    [intl],
  );

  const form = useForm<CreateTenantFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', displayName: '', adminEmail: '', adminPassword: '' },
  });

  // Reopening the dialog must not show what a previous visit typed — nor the
  // previous visit's one-time password.
  const { reset } = form;
  useEffect(() => {
    if (open) {
      reset({ name: '', displayName: '', adminEmail: '', adminPassword: '' });
      setGeneratedPassword(null);
    }
  }, [open, reset]);

  const submit = form.handleSubmit(async (values) => {
    try {
      const created = await createTenant.mutateAsync({
        name: values.name.trim(),
        displayName: values.displayName.trim(),
        adminEmail: values.adminEmail.trim(),
        // A blank password must be OMITTED (JSON.stringify drops undefined):
        // sending "" would be judged by the password policy instead of
        // triggering server-side generation.
        adminPassword: values.adminPassword || undefined,
      });
      if (created.generatedAdminPassword) {
        // Switch to the one-time reveal instead of closing.
        setGeneratedPassword(created.generatedAdminPassword);
      } else {
        onOpenChange(false);
      }
    } catch {
      // Surfaced by the mutation's error toast (prefers ProblemDetail detail);
      // the dialog stays open so the operator can correct the input.
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        {generatedPassword ? (
          <>
            <DialogHeader>
              <DialogTitle>
                <FormattedMessage id="tenants.create.successTitle" />
              </DialogTitle>
              <DialogDescription>
                <FormattedMessage id="tenants.create.successDescription" />
              </DialogDescription>
            </DialogHeader>

            <Alert variant="warning" appearance="light" size="sm">
              <AlertIcon>
                <TriangleAlert />
              </AlertIcon>
              <AlertContent>
                <AlertTitle>
                  <FormattedMessage id="tenants.create.oneTimeTitle" />
                </AlertTitle>
                <AlertDescription>
                  <FormattedMessage id="tenants.create.oneTimeWarning" />
                </AlertDescription>
              </AlertContent>
            </Alert>

            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium">
                <FormattedMessage id="tenants.create.generatedPasswordLabel" />
              </span>
              <div className="flex items-center gap-2">
                <code className="grow rounded-md border bg-muted px-3 py-2 font-mono text-sm break-all select-all">
                  {generatedPassword}
                </code>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => copyToClipboard(generatedPassword)}
                >
                  {isCopied ? (
                    <Check className="size-4" />
                  ) : (
                    <Copy className="size-4" />
                  )}
                  <FormattedMessage
                    id={
                      isCopied
                        ? 'tenants.create.copied'
                        : 'tenants.create.copyPassword'
                    }
                  />
                </Button>
              </div>
            </div>

            <DialogFooter>
              <Button type="button" onClick={() => onOpenChange(false)}>
                <FormattedMessage id="tenants.create.close" />
              </Button>
            </DialogFooter>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>
                <FormattedMessage id="tenants.create.title" />
              </DialogTitle>
              <DialogDescription>
                <FormattedMessage id="tenants.create.description" />
              </DialogDescription>
            </DialogHeader>

            <Form {...form}>
              <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="tenants.create.name" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="off"
                          placeholder={intl.formatMessage({
                            id: 'tenants.create.namePlaceholder',
                          })}
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="tenants.create.nameHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="displayName"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="tenants.create.displayName" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="off"
                          placeholder={intl.formatMessage({
                            id: 'tenants.create.displayNamePlaceholder',
                          })}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="adminEmail"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="tenants.create.adminEmail" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="email"
                          autoComplete="off"
                          placeholder={intl.formatMessage({
                            id: 'tenants.create.adminEmailPlaceholder',
                          })}
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="tenants.create.adminEmailHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="adminPassword"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="tenants.create.adminPassword" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="password"
                          autoComplete="new-password"
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="tenants.create.adminPasswordHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <DialogFooter>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => onOpenChange(false)}
                  >
                    <FormattedMessage id="tenants.create.cancel" />
                  </Button>
                  <Button type="submit" disabled={isSubmitting}>
                    {isSubmitting && (
                      <LoaderCircle className="size-4 animate-spin" />
                    )}
                    <FormattedMessage
                      id={
                        isSubmitting
                          ? 'tenants.create.submitting'
                          : 'tenants.create.submit'
                      }
                    />
                  </Button>
                </DialogFooter>
              </form>
            </Form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}
