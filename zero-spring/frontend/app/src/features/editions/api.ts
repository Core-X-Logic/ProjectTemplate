import { apiFetch } from '@/api/client';
import type {
  CreateEditionRequest,
  EditionDetailDto,
  EditionListParams,
  FeatureDefinitionDto,
  FeatureValueDto,
  PageEditionDto,
  UpdateEditionRequest,
} from './types';

/**
 * Editions endpoint wrappers (CONTRACT-phase5.md §A.1 table).
 *
 * Thin, typed functions over `apiFetch` — auth/tenant/locale headers, the 401
 * refresh dance and ProblemDetail → `ApiError` mapping all live in the client.
 * Every write endpoint is `Side.HOST` + `editions.manage` on the backend; the
 * UI mirrors that with `<Can>` guards (double lock).
 */

const EDITIONS_URL = '/api/editions';

/** `GET /api/editions` — paged edition list (Spring `Pageable` query params). */
export function listEditions(
  params: EditionListParams = {},
): Promise<PageEditionDto> {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  if (params.sort) {
    query.set('sort', params.sort);
  }
  const qs = query.toString();
  return apiFetch<PageEditionDto>(qs ? `${EDITIONS_URL}?${qs}` : EDITIONS_URL);
}

/** `GET /api/editions/{id}` — edition plus its resolved feature values. */
export function getEditionById(id: number): Promise<EditionDetailDto> {
  return apiFetch<EditionDetailDto>(`${EDITIONS_URL}/${id}`);
}

/** `POST /api/editions` — create an edition (`editions.manage`). */
export function createEdition(
  body: CreateEditionRequest,
): Promise<EditionDetailDto> {
  return apiFetch<EditionDetailDto>(EDITIONS_URL, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/editions/{id}` — prices stay editable after creation. */
export function updateEdition(
  id: number,
  body: UpdateEditionRequest,
): Promise<EditionDetailDto> {
  return apiFetch<EditionDetailDto>(`${EDITIONS_URL}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/**
 * `DELETE /api/editions/{id}`. The backend answers 409 when the edition is in
 * use by a subscription or referenced as another edition's expiring target;
 * the mutation hook surfaces that ProblemDetail `detail` verbatim in a toast.
 */
export function removeEdition(id: number): Promise<void> {
  return apiFetch<void>(`${EDITIONS_URL}/${id}`, { method: 'DELETE' });
}

/** `PUT /api/editions/{id}/features` — batch feature-value assignment. */
export function setEditionFeatures(
  id: number,
  values: FeatureValueDto[],
): Promise<EditionDetailDto> {
  return apiFetch<EditionDetailDto>(`${EDITIONS_URL}/${id}/features`, {
    method: 'PUT',
    body: JSON.stringify(values),
  });
}

/**
 * `GET /api/features/definitions` — the feature registry (name, type, default).
 * Drives the input kind rendered by the feature-value editors on both the
 * edition form and the tenant override panel.
 */
export function getFeatureDefinitions(): Promise<FeatureDefinitionDto[]> {
  return apiFetch<FeatureDefinitionDto[]>('/api/features/definitions');
}
