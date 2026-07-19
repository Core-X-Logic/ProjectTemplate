import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle, TriangleAlert } from 'lucide-react';
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
import { z } from 'zod';
import { useCreateTenant } from '../hooks';
import { TENANT_NAME_PATTERN } from '../types';

interface CreateTenantFormValues {
  name: string;
  displayName: string;
}

export interface CreateTenantDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Tenant creation dialog over `POST /api/tenants`.
 *
 * Carries a standing warning about Issue #1: creating a tenant provisions the
 * tenant row and its default subscription, but NOT a user. The operator would
 * otherwise only discover the gap when the new tenant turns out to have nobody
 * who can sign in — so the screen says it before the fact, not after.
 *
 * `name` is validated against the backend's `@Pattern` locally; a duplicate
 * name is a 409 that only the server can detect, and its ProblemDetail `detail`
 * reaches the operator through the mutation's error toast.
 */
export function CreateTenantDialog({
  open,
  onOpenChange,
}: CreateTenantDialogProps) {
  const intl = useIntl();
  const createTenant = useCreateTenant();

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
      }),
    [intl],
  );

  const form = useForm<CreateTenantFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', displayName: '' },
  });

  // Reopening the dialog must not show what was typed during a previous visit.
  const { reset } = form;
  useEffect(() => {
    if (open) {
      reset({ name: '', displayName: '' });
    }
  }, [open, reset]);

  const submit = form.handleSubmit(async (values) => {
    try {
      await createTenant.mutateAsync({
        name: values.name.trim(),
        displayName: values.displayName.trim(),
      });
      onOpenChange(false);
    } catch {
      // Surfaced by the mutation's error toast (prefers ProblemDetail detail);
      // the dialog stays open so the operator can correct the name.
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="tenants.create.title" />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage id="tenants.create.description" />
          </DialogDescription>
        </DialogHeader>

        <Alert variant="warning" appearance="light" size="sm">
          <AlertIcon>
            <TriangleAlert />
          </AlertIcon>
          <AlertContent>
            <AlertTitle>
              <FormattedMessage id="tenants.create.noAdminTitle" />
            </AlertTitle>
            <AlertDescription>
              <FormattedMessage id="tenants.create.noAdminDescription" />
            </AlertDescription>
          </AlertContent>
        </Alert>

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
      </DialogContent>
    </Dialog>
  );
}
