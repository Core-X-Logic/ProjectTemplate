import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { z } from 'zod';
import { ApiError } from '@/api/client';
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
import { RoleSelect } from '@/features/users/components/role-select';
import { useInviteUser } from '@/features/users/invitation-hooks';

interface InviteFormValues {
  username: string;
  email: string;
  roleNames: string[];
}

interface InviteUserDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const EMPTY_VALUES: InviteFormValues = {
  username: '',
  email: '',
  roleNames: [],
};

/**
 * "Invite user" dialog: the admin fixes username + roles, the backend
 * mails a single-use token, and the invitee chooses their own password on
 * `/account/accept-invitation`. Deliberately NO password field here — that is
 * the entire difference from `UserFormDialog`.
 */
export function InviteUserDialog({ open, onOpenChange }: InviteUserDialogProps) {
  const intl = useIntl();
  const invite = useInviteUser();

  const schema = useMemo(
    () =>
      z.object({
        username: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        email: z.email(intl.formatMessage({ id: 'users.form.emailInvalid' })),
        roleNames: z.array(z.string()),
      }),
    [intl],
  );

  const form = useForm<InviteFormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY_VALUES,
  });

  useEffect(() => {
    if (open) {
      form.reset(EMPTY_VALUES);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const submit = form.handleSubmit(async (values) => {
    try {
      await invite.mutateAsync({
        username: values.username.trim(),
        email: values.email.trim(),
        roleNames: values.roleNames,
      });
      onOpenChange(false);
    } catch (error) {
      // The toast is raised by the mutation hook; surface the ProblemDetail
      // (409 duplicate pending / existing user) at the field level too.
      if (error instanceof ApiError && error.detail) {
        form.setError('email', { message: error.detail });
      }
    }
  });

  const isSubmitting = form.formState.isSubmitting || invite.isPending;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="users.invite.title" />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage id="users.invite.description" />
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
            <FormField
              control={form.control}
              name="username"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.username" />
                  </FormLabel>
                  <FormControl>
                    <Input {...field} autoComplete="off" />
                  </FormControl>
                  <FormDescription>
                    <FormattedMessage id="users.invite.usernameHint" />
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="email"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.email" />
                  </FormLabel>
                  <FormControl>
                    <Input {...field} type="email" autoComplete="off" />
                  </FormControl>
                  <FormDescription>
                    <FormattedMessage id="users.invite.emailHint" />
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="roleNames"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.roles" />
                  </FormLabel>
                  <FormControl>
                    <RoleSelect value={field.value} onChange={field.onChange} />
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
                disabled={isSubmitting}
              >
                <FormattedMessage id="users.form.cancel" />
              </Button>
              <Can permission="users.create">
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting && (
                    <LoaderCircle className="size-4 animate-spin" />
                  )}
                  <FormattedMessage id="users.invite.submit" />
                </Button>
              </Can>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
