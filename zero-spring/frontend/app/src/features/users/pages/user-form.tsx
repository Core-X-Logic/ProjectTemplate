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
import { OuSelect } from '@/features/users/components/ou-select';
import { RoleSelect } from '@/features/users/components/role-select';
import {
  useAssignOrganizationUnits,
  useCreateUser,
  useUpdateUser,
} from '@/features/users/hooks';
import type { UserDto } from '@/features/users/types';

interface UserFormValues {
  username: string;
  email: string;
  name: string;
  surname: string;
  phoneNumber: string;
  password: string;
  roleNames: string[];
  organizationUnitIds: number[];
}

interface UserFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** `null`/`undefined` → create mode; a user → edit mode. */
  user?: UserDto | null;
}

function toDefaults(user: UserDto | null | undefined): UserFormValues {
  return {
    username: user?.username ?? '',
    email: user?.email ?? '',
    name: user?.name ?? '',
    surname: user?.surname ?? '',
    phoneNumber: user?.phoneNumber ?? '',
    password: '',
    roleNames: user?.roles ?? [],
    organizationUnitIds: [],
  };
}

/**
 * Create/edit user dialog (react-hook-form + zod).
 *
 * Create maps to `CreateUserRequest` (all fields). Edit maps to
 * `UpdateUserRequest` (email/password/active/roleNames only — identity fields
 * are read-only on the admin API), plus `PUT {id}/organization-units` when the
 * OU selection was actually touched (`UserDto` does not expose memberships).
 */
export function UserFormDialog({ open, onOpenChange, user }: UserFormDialogProps) {
  const intl = useIntl();
  const isEdit = Boolean(user);

  const createUser = useCreateUser();
  const updateUser = useUpdateUser();
  const assignOus = useAssignOrganizationUnits();

  const schema = useMemo(() => {
    const required = intl.formatMessage({ id: 'validation.required' });
    const passwordMin = intl.formatMessage({ id: 'users.form.passwordMin' });
    return z.object({
      username: isEdit ? z.string() : z.string().min(1, required),
      email: z.email(intl.formatMessage({ id: 'users.form.emailInvalid' })),
      name: z.string(),
      surname: z.string(),
      phoneNumber: z.string(),
      password: isEdit
        ? z.union([z.literal(''), z.string().min(8, passwordMin)])
        : z.string().min(8, passwordMin),
      roleNames: z.array(z.string()),
      organizationUnitIds: z.array(z.number()),
    });
  }, [intl, isEdit]);

  const form = useForm<UserFormValues>({
    resolver: zodResolver(schema),
    defaultValues: toDefaults(user),
  });

  // Re-seed when the dialog is (re)opened for a different user.
  useEffect(() => {
    if (open) {
      form.reset(toDefaults(user));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, user]);

  const applyFieldErrors = (error: unknown) => {
    if (error instanceof ApiError && error.fields) {
      for (const [field, messages] of Object.entries(error.fields)) {
        if (field in toDefaults(null)) {
          form.setError(field as keyof UserFormValues, {
            message: messages.join(' '),
          });
        }
      }
    }
  };

  const submit = form.handleSubmit(async (values) => {
    try {
      if (isEdit && user?.id !== undefined) {
        await updateUser.mutateAsync({
          id: user.id,
          body: {
            email: values.email,
            password: values.password || undefined,
            active: user.active,
            roleNames: values.roleNames,
          },
        });
        if (form.formState.dirtyFields.organizationUnitIds) {
          await assignOus.mutateAsync({
            id: user.id,
            body: { ouIds: values.organizationUnitIds },
          });
        }
      } else {
        await createUser.mutateAsync({
          username: values.username,
          email: values.email,
          password: values.password,
          name: values.name || undefined,
          surname: values.surname || undefined,
          phoneNumber: values.phoneNumber || undefined,
          roleNames: values.roleNames,
          organizationUnitIds: values.organizationUnitIds,
        });
      }
      onOpenChange(false);
    } catch (error) {
      // Toasts are raised by the mutation hooks; map field errors to the form.
      applyFieldErrors(error);
    }
  });

  const isSubmitting =
    form.formState.isSubmitting ||
    createUser.isPending ||
    updateUser.isPending ||
    assignOus.isPending;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage
              id={isEdit ? 'users.form.editTitle' : 'users.form.createTitle'}
            />
          </DialogTitle>
          {isEdit && (
            <DialogDescription>
              <FormattedMessage id="users.form.identityReadonly" />
            </DialogDescription>
          )}
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
            <FormField
              control={form.control}
              name="username"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.username" />
                  </FormLabel>
                  <FormControl>
                    <Input {...field} autoComplete="off" disabled={isEdit} />
                  </FormControl>
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
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="users.form.name" />
                    </FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" disabled={isEdit} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="surname"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="users.form.surname" />
                    </FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" disabled={isEdit} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            <FormField
              control={form.control}
              name="phoneNumber"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.phoneNumber" />
                  </FormLabel>
                  <FormControl>
                    <Input {...field} autoComplete="off" disabled={isEdit} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="password"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.password" />
                  </FormLabel>
                  <FormControl>
                    <Input {...field} type="password" autoComplete="new-password" />
                  </FormControl>
                  {isEdit && (
                    <FormDescription>
                      <FormattedMessage id="users.form.passwordEditHint" />
                    </FormDescription>
                  )}
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

            <FormField
              control={form.control}
              name="organizationUnitIds"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="users.form.organizationUnits" />
                  </FormLabel>
                  <FormControl>
                    <OuSelect value={field.value} onChange={field.onChange} />
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
              <Can permission={isEdit ? 'users.update' : 'users.create'}>
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting && (
                    <LoaderCircle className="size-4 animate-spin" />
                  )}
                  <FormattedMessage
                    id={
                      isEdit
                        ? 'users.form.submitEdit'
                        : 'users.form.submitCreate'
                    }
                  />
                </Button>
              </Can>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
