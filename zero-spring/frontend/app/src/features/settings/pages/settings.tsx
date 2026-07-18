import { useEffect } from 'react';
import { LoaderCircle } from 'lucide-react';
import { useForm, useFieldArray } from 'react-hook-form';
import { Helmet } from 'react-helmet-async';
import { useIntl } from 'react-intl';
import { Can, usePermission } from '@/auth/rbac';
import { RequireAuth } from '@/auth/require-auth';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardHeading,
  CardTitle,
} from '@/components/ui/card';
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  useHostSettings,
  useTenantSettings,
  useUpdateHostSettings,
  useUpdateTenantSettings,
} from '@/features/settings/hooks';
import { useSettingsMessages } from '@/features/settings/messages';
import type { SettingDto, SettingUpdate } from '@/features/settings/types';

const TENANT_PERMISSION = 'settings.tenant.manage';
const HOST_PERMISSION = 'settings.host.manage';

interface SettingsFormValues {
  items: { name: string; value: string; defaultValue: string }[];
}

function toFormValues(settings: SettingDto[]): SettingsFormValues {
  return {
    items: settings.map((setting) => ({
      name: setting.name ?? '',
      value: setting.value ?? '',
      defaultValue: setting.defaultValue ?? '',
    })),
  };
}

interface SettingsFormProps {
  settings: SettingDto[];
  managePermission: string;
  isSaving: boolean;
  onSave: (items: SettingUpdate[]) => void;
}

/**
 * Editable list of settings for a single scope (react-hook-form + field array).
 *
 * Only mounted once the scope's settings have loaded, so the form seeds its
 * defaults from `settings` directly and re-seeds on every refetch. Save submits
 * ONLY the changed entries (via `dirtyFields`); an emptied value is forwarded
 * verbatim so the backend drops the override and the setting falls back to its
 * default.
 */
function SettingsForm({
  settings,
  managePermission,
  isSaving,
  onSave,
}: SettingsFormProps) {
  const intl = useIntl();
  const t = useSettingsMessages();

  const form = useForm<SettingsFormValues>({
    defaultValues: toFormValues(settings),
  });
  const { fields } = useFieldArray({ control: form.control, name: 'items' });

  // Re-seed when a refetch delivers a fresh snapshot (e.g. after Save).
  useEffect(() => {
    form.reset(toFormValues(settings));
  }, [settings, form]);

  const labelFor = (name: string) =>
    intl.formatMessage({ id: `settings.field.${name}`, defaultMessage: name });

  const submit = form.handleSubmit((values) => {
    const dirtyItems = form.formState.dirtyFields.items ?? [];
    const changed: SettingUpdate[] = [];
    values.items.forEach((item, index) => {
      if (dirtyItems[index]?.value === true) {
        changed.push({
          name: settings[index]?.name ?? item.name,
          value: item.value,
        });
      }
    });
    if (changed.length === 0) {
      return;
    }
    onSave(changed);
  });

  return (
    <Form {...form}>
      <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
        <div className="flex flex-col gap-4">
          {fields.map((field, index) => (
            <FormField
              key={field.id}
              control={form.control}
              name={`items.${index}.value` as const}
              render={({ field: valueField }) => (
                <FormItem>
                  <FormLabel>{labelFor(field.name)}</FormLabel>
                  <FormControl>
                    <Input
                      {...valueField}
                      placeholder={field.defaultValue || undefined}
                      autoComplete="off"
                    />
                  </FormControl>
                  {field.defaultValue ? (
                    <FormDescription>
                      {t('settings.default', { value: field.defaultValue })}
                    </FormDescription>
                  ) : null}
                </FormItem>
              )}
            />
          ))}
        </div>

        <div className="flex justify-end">
          <Can permission={managePermission}>
            <Button type="submit" disabled={isSaving || !form.formState.isDirty}>
              {isSaving && <LoaderCircle className="size-4 animate-spin" />}
              {t('settings.save')}
            </Button>
          </Can>
        </div>
      </form>
    </Form>
  );
}

interface SettingsPanelProps {
  managePermission: string;
  settings: SettingDto[] | undefined;
  isLoading: boolean;
  isError: boolean;
  isSaving: boolean;
  onSave: (items: SettingUpdate[]) => void;
}

/** Loading / error / empty / form gate shared by both scope panels. */
function SettingsPanel({
  managePermission,
  settings,
  isLoading,
  isError,
  isSaving,
  onSave,
}: SettingsPanelProps) {
  const t = useSettingsMessages();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-14">
        <LoaderCircle className="size-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (isError) {
    return (
      <p role="alert" className="py-14 text-center text-sm text-destructive">
        {t('settings.loadError')}
      </p>
    );
  }

  if (!settings || settings.length === 0) {
    return (
      <p className="py-14 text-center text-sm text-muted-foreground">
        {t('settings.empty')}
      </p>
    );
  }

  return (
    <SettingsForm
      settings={settings}
      managePermission={managePermission}
      isSaving={isSaving}
      onSave={onSave}
    />
  );
}

/** Tenant-scope panel — owns its own query + mutation. */
function TenantSettingsPanel() {
  const { data, isLoading, isError } = useTenantSettings();
  const update = useUpdateTenantSettings();

  return (
    <SettingsPanel
      managePermission={TENANT_PERMISSION}
      settings={data}
      isLoading={isLoading}
      isError={isError}
      isSaving={update.isPending}
      onSave={(items) => update.mutate(items)}
    />
  );
}

/** Host-scope panel — owns its own query + mutation. */
function HostSettingsPanel() {
  const { data, isLoading, isError } = useHostSettings();
  const update = useUpdateHostSettings();

  return (
    <SettingsPanel
      managePermission={HOST_PERMISSION}
      settings={data}
      isLoading={isLoading}
      isError={isError}
      isSaving={update.isPending}
      onSave={(items) => update.mutate(items)}
    />
  );
}

function SettingsContent() {
  const t = useSettingsMessages();
  const canTenant = usePermission(TENANT_PERMISSION);

  // Default to the first scope the user may actually manage: a host-only
  // operator (no tenant.manage) lands on the Host tab rather than an
  // inaccessible Tenant tab. The page guard guarantees at least one scope.
  const defaultTab = canTenant ? 'tenant' : 'host';

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{t('settings.title')}</title>
      </Helmet>

      <Card>
        <CardHeader>
          <CardHeading>
            <CardTitle>{t('settings.title')}</CardTitle>
            <CardDescription>{t('settings.subtitle')}</CardDescription>
          </CardHeading>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue={defaultTab}>
            <TabsList variant="line">
              <Can permission={TENANT_PERMISSION}>
                <TabsTrigger value="tenant">
                  {t('settings.tabs.tenant')}
                </TabsTrigger>
              </Can>
              <Can permission={HOST_PERMISSION}>
                <TabsTrigger value="host">{t('settings.tabs.host')}</TabsTrigger>
              </Can>
            </TabsList>

            <Can permission={TENANT_PERMISSION}>
              <TabsContent value="tenant">
                <TenantSettingsPanel />
              </TabsContent>
            </Can>

            <Can permission={HOST_PERMISSION}>
              <TabsContent value="host">
                <HostSettingsPanel />
              </TabsContent>
            </Can>
          </Tabs>
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * Settings page — reachable with EITHER `settings.tenant.manage` OR
 * `settings.host.manage` (any-of); each scope tab is then individually gated
 * (double lock with the backend `@PreAuthorize`).
 */
export function SettingsPage() {
  return (
    <RequireAuth anyPermission={[TENANT_PERMISSION, HOST_PERMISSION]}>
      <SettingsContent />
    </RequireAuth>
  );
}
