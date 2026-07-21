import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { Can } from '@/auth/rbac';
import { PageHeader } from '@/components/common/page-header';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardHeading,
  CardTitle,
} from '@/components/ui/card';
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
import { Textarea } from '@/components/ui/textarea';
import { FeatureValuesEditor } from '../components/feature-values-editor';
import { useCreateEdition, useEdition, useEditions, useUpdateEdition } from '../hooks';

/**
 * Edition create/edit form (F5 slice A) — rhf + zod.
 *
 * Routing contract: `/editions/new` (create) and `/editions/{id}` (edit); the
 * page derives its mode from the `:id` param. The technical `name` is immutable
 * after creation (`UpdateEditionRequest` carries no name), so the field is
 * disabled in edit mode — same shape as the role form.
 *
 * Numeric/optional fields travel as strings inside the form and are coerced on
 * submit: an empty box means "not set" (`undefined` on the wire) rather than 0,
 * which is what the backend needs to tell "free" from "priced at zero".
 *
 * Feature values are edited by a dedicated batch editor that needs an existing
 * edition id, so it only mounts in edit mode.
 */
export function EditionFormPage() {
  return <EditionFormContent />;
}

/** Native select styling, aligned with the `Input` primitive. */
const SELECT_CLASS =
  'flex h-9 w-full items-center rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground shadow-xs shadow-black/5 outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50';

/** Sentinel for "no expiring edition" — native selects cannot hold `null`. */
const NO_EXPIRING_EDITION = '';

interface EditionFormValues {
  name: string;
  displayName: string;
  description: string;
  monthlyPrice: string;
  annualPrice: string;
  currency: string;
  trialDayCount: string;
  graceDayCount: string;
  expiringEditionId: string;
  sortOrder: string;
  isActive: boolean;
}

const EMPTY_VALUES: EditionFormValues = {
  name: '',
  displayName: '',
  description: '',
  monthlyPrice: '',
  annualPrice: '',
  currency: '',
  trialDayCount: '',
  graceDayCount: '',
  expiringEditionId: NO_EXPIRING_EDITION,
  sortOrder: '',
  isActive: true,
};

/** `''` → `undefined`, otherwise the parsed number. */
function toNumber(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : Number(trimmed);
}

/** `''` → `undefined`, otherwise the trimmed string. */
function toText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

function EditionFormContent() {
  const intl = useIntl();
  const navigate = useNavigate();

  const { id: idParam } = useParams<{ id: string }>();
  const parsedId =
    idParam !== undefined && idParam !== 'new' ? Number(idParam) : Number.NaN;
  const editionId = Number.isFinite(parsedId) ? parsedId : undefined;
  const isEdit = editionId !== undefined;

  const {
    data: detail,
    isLoading: detailLoading,
    isError: detailError,
  } = useEdition(editionId);

  // Candidate list for the expiring-edition selector. The backend rejects a
  // non-free target with 400, so the options are filtered to free editions
  // (self excluded) — the rule is enforced server-side either way.
  const { data: editionPage } = useEditions({ page: 0, size: 100 });
  const expiringOptions = useMemo(
    () =>
      (editionPage?.content ?? []).filter(
        (edition) => edition.free === true && edition.id !== editionId,
      ),
    [editionPage, editionId],
  );

  const createEdition = useCreateEdition();
  const updateEdition = useUpdateEdition();

  const schema = useMemo(() => {
    const required = intl.formatMessage({ id: 'validation.required' });
    const numberError = intl.formatMessage({ id: 'editions.form.numberError' });
    const optionalNonNegative = z.string().refine((value) => {
      const trimmed = value.trim();
      if (trimmed === '') {
        return true;
      }
      const parsed = Number(trimmed);
      return Number.isFinite(parsed) && parsed >= 0;
    }, numberError);

    return z.object({
      name: isEdit ? z.string() : z.string().trim().min(1, required),
      displayName: z.string().trim().min(1, required),
      description: z.string(),
      monthlyPrice: optionalNonNegative,
      annualPrice: optionalNonNegative,
      currency: z.string(),
      trialDayCount: optionalNonNegative,
      graceDayCount: optionalNonNegative,
      expiringEditionId: z.string(),
      sortOrder: optionalNonNegative,
      isActive: z.boolean(),
    });
  }, [intl, isEdit]);

  const form = useForm<EditionFormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY_VALUES,
  });

  // Populate once the edition detail arrives (edit mode).
  useEffect(() => {
    const edition = detail?.edition;
    if (isEdit && edition) {
      form.reset({
        name: edition.name ?? '',
        displayName: edition.displayName ?? '',
        description: edition.description ?? '',
        monthlyPrice:
          edition.monthlyPrice !== undefined ? String(edition.monthlyPrice) : '',
        annualPrice:
          edition.annualPrice !== undefined ? String(edition.annualPrice) : '',
        currency: edition.currency ?? '',
        trialDayCount:
          edition.trialDayCount !== undefined
            ? String(edition.trialDayCount)
            : '',
        graceDayCount:
          edition.graceDayCount !== undefined
            ? String(edition.graceDayCount)
            : '',
        expiringEditionId:
          edition.expiringEditionId !== undefined
            ? String(edition.expiringEditionId)
            : NO_EXPIRING_EDITION,
        sortOrder:
          edition.sortOrder !== undefined ? String(edition.sortOrder) : '',
        isActive: edition.active ?? true,
      });
    }
  }, [isEdit, detail, form]);

  const submit = form.handleSubmit(async (values) => {
    const shared = {
      displayName: values.displayName,
      description: toText(values.description),
      monthlyPrice: toNumber(values.monthlyPrice),
      annualPrice: toNumber(values.annualPrice),
      currency: toText(values.currency),
      trialDayCount: toNumber(values.trialDayCount),
      graceDayCount: toNumber(values.graceDayCount),
      expiringEditionId: toNumber(values.expiringEditionId),
      active: values.isActive,
      sortOrder: toNumber(values.sortOrder),
    };

    try {
      if (isEdit) {
        await updateEdition.mutateAsync({ id: editionId, body: shared });
      } else {
        await createEdition.mutateAsync({ name: values.name, ...shared });
      }
      navigate('/editions');
    } catch (error) {
      // The mutation hook already raised an error toast; map server-side field
      // validation errors back onto the form.
      if (error instanceof ApiError && error.fields) {
        for (const [field, messages] of Object.entries(error.fields)) {
          if (field in EMPTY_VALUES) {
            form.setError(field as keyof EditionFormValues, {
              message: messages.join(' '),
            });
          }
        }
      }
    }
  });

  const title = intl.formatMessage({
    id: isEdit ? 'editions.form.editTitle' : 'editions.form.createTitle',
  });
  const isSubmitting = form.formState.isSubmitting;

  if (isEdit && detailLoading) {
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

  if (isEdit && detailError) {
    return (
      <div className="container-fluid">
        <PageHeader title={title} />
        <Card>
          <CardContent className="flex flex-col items-start gap-4 py-8">
            <p role="alert" className="text-sm text-destructive">
              <FormattedMessage id="editions.form.loadError" />
            </p>
            <Button variant="outline" onClick={() => navigate('/editions')}>
              <FormattedMessage id="editions.form.cancel" />
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
        description={
          isEdit && detail?.edition?.name ? detail.edition.name : undefined
        }
      />

      <div className="flex flex-col gap-6">
        <Card>
          <Form {...form}>
          <form onSubmit={submit} noValidate>
            <CardContent className="flex flex-col gap-6 py-5">
              <section className="flex flex-col gap-5">
              <h2 className="text-sm font-semibold tracking-tight text-foreground">
                <FormattedMessage id="editions.form.sectionGeneral" />
              </h2>
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="editions.form.name" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        disabled={isEdit}
                        autoComplete="off"
                        placeholder={intl.formatMessage({
                          id: 'editions.form.namePlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormDescription>
                      <FormattedMessage id="editions.form.nameHint" />
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
                      <FormattedMessage id="editions.form.displayName" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        autoComplete="off"
                        placeholder={intl.formatMessage({
                          id: 'editions.form.displayNamePlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="description"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="editions.form.description" />
                    </FormLabel>
                    <FormControl>
                      <Textarea
                        {...field}
                        rows={3}
                        placeholder={intl.formatMessage({
                          id: 'editions.form.descriptionPlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              </section>

              <section className="flex flex-col gap-5">
              <h2 className="text-sm font-semibold tracking-tight text-foreground">
                <FormattedMessage id="editions.form.sectionPricing" />
              </h2>
              <div className="grid gap-5 sm:grid-cols-3">
                <FormField
                  control={form.control}
                  name="monthlyPrice"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.monthlyPrice" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="number"
                          inputMode="decimal"
                          min={0}
                          step="0.01"
                          autoComplete="off"
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="editions.form.priceHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="annualPrice"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.annualPrice" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="number"
                          inputMode="decimal"
                          min={0}
                          step="0.01"
                          autoComplete="off"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="currency"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.currency" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="off"
                          maxLength={3}
                          placeholder={intl.formatMessage({
                            id: 'editions.form.currencyPlaceholder',
                          })}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              </section>

              <section className="flex flex-col gap-5">
              <h2 className="text-sm font-semibold tracking-tight text-foreground">
                <FormattedMessage id="editions.form.sectionLifecycle" />
              </h2>
              <div className="grid gap-5 sm:grid-cols-3">
                <FormField
                  control={form.control}
                  name="trialDayCount"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.trialDayCount" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="number"
                          inputMode="numeric"
                          min={0}
                          autoComplete="off"
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="editions.form.trialHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="graceDayCount"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.graceDayCount" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="number"
                          inputMode="numeric"
                          min={0}
                          autoComplete="off"
                        />
                      </FormControl>
                      <FormDescription>
                        <FormattedMessage id="editions.form.graceHint" />
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <FormField
                  control={form.control}
                  name="sortOrder"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        <FormattedMessage id="editions.form.sortOrder" />
                      </FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="number"
                          inputMode="numeric"
                          min={0}
                          autoComplete="off"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <FormField
                control={form.control}
                name="expiringEditionId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel htmlFor="edition-expiring">
                      <FormattedMessage id="editions.form.expiringEdition" />
                    </FormLabel>
                    <FormControl>
                      {/* Native select: no extra dependency, fully keyboard and
                          screen-reader accessible, and trivially testable. */}
                      <select
                        {...field}
                        id="edition-expiring"
                        className={SELECT_CLASS}
                      >
                        <option value={NO_EXPIRING_EDITION}>
                          {intl.formatMessage({
                            id: 'editions.form.expiringEditionNone',
                          })}
                        </option>
                        {expiringOptions.map((edition) => (
                          <option key={edition.id} value={String(edition.id)}>
                            {edition.displayName || edition.name}
                          </option>
                        ))}
                      </select>
                    </FormControl>
                    <FormDescription>
                      <FormattedMessage id="editions.form.expiringEditionHint" />
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="isActive"
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
                        <FormattedMessage id="editions.form.isActive" />
                      </FormLabel>
                      <FormDescription>
                        <FormattedMessage id="editions.form.isActiveHint" />
                      </FormDescription>
                    </div>
                  </FormItem>
                )}
              />
              </section>
            </CardContent>

            <CardFooter className="justify-end gap-2.5 py-5">
              <Button
                type="button"
                variant="outline"
                onClick={() => navigate('/editions')}
              >
                <FormattedMessage id="editions.form.cancel" />
              </Button>
              <Can permission="editions.manage">
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting && (
                    <LoaderCircle className="size-4 animate-spin" />
                  )}
                  <FormattedMessage
                    id={
                      isSubmitting
                        ? 'editions.form.saving'
                        : isEdit
                          ? 'editions.form.submitUpdate'
                          : 'editions.form.submitCreate'
                    }
                  />
                </Button>
              </Can>
            </CardFooter>
          </form>
        </Form>
      </Card>

      <Card>
        <CardHeader className="py-5">
          <CardHeading>
            <CardTitle>
              <FormattedMessage id="editions.features.title" />
            </CardTitle>
            <CardDescription>
              <FormattedMessage id="editions.features.description" />
            </CardDescription>
          </CardHeading>
        </CardHeader>
        <CardContent className="py-5">
          {isEdit && editionId !== undefined ? (
            <FeatureValuesEditor
              editionId={editionId}
              values={detail?.features ?? []}
            />
          ) : (
            <p className="text-sm text-muted-foreground">
              <FormattedMessage id="editions.features.createHint" />
            </p>
          )}
        </CardContent>
        </Card>
      </div>
    </div>
  );
}
