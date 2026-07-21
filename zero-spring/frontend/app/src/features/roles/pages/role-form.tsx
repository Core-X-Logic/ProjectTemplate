import { useEffect, useMemo } from 'react';
import { ApiError } from '@/api/client';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { PageHeader } from '@/components/common/page-header';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
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
import { Skeleton } from '@/components/ui/skeleton';
import { Can } from '@/auth/rbac';
import {
  PermissionTree,
  PermissionTreeSkeleton,
} from '../components/permission-tree';
import {
  useCreateRole,
  usePermissionTree,
  useRole,
  useUpdateRole,
} from '../hooks';

/**
 * Role create/edit form (slice B) — rhf + zod, permission tree selector.
 *
 * Routing contract: `/roles/new` (create) and `/roles/{id}` (edit); the page
 * derives its mode from the `:id` param, so both routes can point here. The
 * technical `name` is immutable after creation (`UpdateRoleRequest` carries no
 * name), hence the field is disabled in edit mode.
 *
 * i18n: the roles catalogue is merged into the global `i18n/messages/{en,tr}.ts`
 * and served by the app-level `I18nProvider`, so no feature-scoped provider is
 * needed here.
 */
export function RoleFormPage() {
  return <RoleFormContent />;
}

interface RoleFormValues {
  name: string;
  displayName: string;
  isDefault: boolean;
  permissions: string[];
}

function RoleFormContent() {
  const intl = useIntl();
  const navigate = useNavigate();

  const { id: idParam } = useParams<{ id: string }>();
  const parsedId =
    idParam !== undefined && idParam !== 'new' ? Number(idParam) : Number.NaN;
  const roleId = Number.isFinite(parsedId) ? parsedId : undefined;
  const isEdit = roleId !== undefined;

  const {
    data: role,
    isLoading: roleLoading,
    isError: roleError,
  } = useRole(roleId);
  const {
    data: tree,
    isLoading: treeLoading,
    isError: treeError,
  } = usePermissionTree();

  const createRole = useCreateRole();
  const updateRole = useUpdateRole();

  const schema = useMemo(() => {
    const required = intl.formatMessage({ id: 'validation.required' });
    return z.object({
      name: isEdit ? z.string() : z.string().trim().min(1, required),
      displayName: z.string().trim().min(1, required),
      isDefault: z.boolean(),
      permissions: z.array(z.string()),
    });
  }, [intl, isEdit]);

  const form = useForm<RoleFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '',
      displayName: '',
      isDefault: false,
      permissions: [],
    },
  });

  // Populate the form once the role detail arrives (edit mode).
  useEffect(() => {
    if (isEdit && role) {
      form.reset({
        name: role.name ?? '',
        displayName: role.displayName ?? '',
        isDefault: role.isDefault ?? false,
        permissions: role.permissions ?? [],
      });
    }
  }, [isEdit, role, form]);

  const submit = form.handleSubmit(async (values) => {
    try {
      if (isEdit) {
        await updateRole.mutateAsync({
          id: roleId,
          body: {
            displayName: values.displayName,
            isDefault: values.isDefault,
            permissions: values.permissions,
          },
        });
      } else {
        await createRole.mutateAsync({
          name: values.name,
          displayName: values.displayName,
          isDefault: values.isDefault,
          permissions: values.permissions,
        });
      }
      navigate('/roles');
    } catch (error) {
      // The mutation hook already raised an error toast; here we only map
      // server-side field validation errors back onto the form.
      if (error instanceof ApiError && error.fields) {
        for (const [field, messages] of Object.entries(error.fields)) {
          if (field === 'name' || field === 'displayName') {
            form.setError(field, { message: messages.join(' ') });
          }
        }
      }
    }
  });

  const title = intl.formatMessage({
    id: isEdit ? 'roles.form.editTitle' : 'roles.form.createTitle',
  });
  const isSubmitting = form.formState.isSubmitting;

  if (isEdit && roleLoading) {
    return (
      <div className="container-fluid">
        <PageHeader title={title} />
        <Card>
          <CardContent className="flex flex-col gap-4 py-8">
            <Skeleton className="h-8 w-56" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-40 w-full" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (isEdit && roleError) {
    return (
      <div className="container-fluid">
        <PageHeader title={title} />
        <Card>
          <CardContent className="flex flex-col items-start gap-4 py-8">
            <p role="alert" className="text-sm text-destructive">
              <FormattedMessage id="roles.form.loadError" />
            </p>
            <Button variant="outline" onClick={() => navigate('/roles')}>
              <FormattedMessage id="roles.form.cancel" />
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{title}</title>
      </Helmet>

      <PageHeader
        title={title}
        description={isEdit && role?.name ? role.name : undefined}
      />

      <Card>
        <Form {...form}>
          <form onSubmit={submit} noValidate>
            <CardContent className="flex flex-col gap-6 py-5">
              <section className="flex flex-col gap-5">
                <h2 className="text-sm font-semibold tracking-tight text-foreground">
                  <FormattedMessage id="roles.form.sectionDetails" />
                </h2>
                <FormField
                  control={form.control}
                  name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="roles.form.name" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        disabled={isEdit}
                        autoComplete="off"
                        placeholder={intl.formatMessage({
                          id: 'roles.form.namePlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormDescription>
                      <FormattedMessage id="roles.form.nameHint" />
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
                      <FormattedMessage id="roles.form.displayName" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        autoComplete="off"
                        placeholder={intl.formatMessage({
                          id: 'roles.form.displayNamePlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="isDefault"
                render={({ field }) => (
                  <FormItem className="flex flex-row items-start gap-2.5 space-y-0">
                    <FormControl>
                      <Checkbox
                        checked={field.value}
                        onCheckedChange={(checked) =>
                          field.onChange(checked === true)
                        }
                      />
                    </FormControl>
                    <div className="flex flex-col gap-1">
                      <FormLabel>
                        <FormattedMessage id="roles.form.isDefault" />
                      </FormLabel>
                      <FormDescription>
                        <FormattedMessage id="roles.form.isDefaultHint" />
                      </FormDescription>
                    </div>
                  </FormItem>
                )}
              />

              </section>

              <section className="flex flex-col gap-4">
                <h2 className="text-sm font-semibold tracking-tight text-foreground">
                  <FormattedMessage id="roles.form.sectionPermissions" />
                </h2>
                <FormField
                  control={form.control}
                  name="permissions"
                  render={({ field }) => (
                    <FormItem>
                      <FormDescription>
                        <FormattedMessage id="roles.form.permissionsHint" />
                      </FormDescription>
                      <FormControl>
                        <div className="max-h-96 overflow-y-auto rounded-lg border border-border p-3.5">
                          {treeLoading ? (
                            <PermissionTreeSkeleton />
                          ) : treeError ? (
                            <p
                              role="alert"
                              className="text-sm text-destructive"
                            >
                              <FormattedMessage id="permission.tree.error" />
                            </p>
                          ) : (
                            <PermissionTree
                              nodes={tree ?? []}
                              value={field.value}
                              onChange={field.onChange}
                            />
                          )}
                        </div>
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage
                          id="permission.tree.selectedCount"
                          values={{ count: field.value.length }}
                        />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </section>
            </CardContent>

            <CardFooter className="sticky bottom-0 z-10 justify-end gap-2.5 bg-card py-5">
              <Button
                type="button"
                variant="outline"
                onClick={() => navigate('/roles')}
              >
                <FormattedMessage id="roles.form.cancel" />
              </Button>
              <Can permission={isEdit ? 'roles.update' : 'roles.create'}>
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting && (
                    <LoaderCircle className="size-4 animate-spin" />
                  )}
                  <FormattedMessage
                    id={
                      isSubmitting
                        ? 'roles.form.saving'
                        : isEdit
                          ? 'roles.form.submitUpdate'
                          : 'roles.form.submitCreate'
                    }
                  />
                </Button>
              </Can>
            </CardFooter>
          </form>
        </Form>
      </Card>
    </div>
  );
}
