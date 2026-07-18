import type { components } from '@/api/schema';

/**
 * Editions feature types (F5 slice A — CONTRACT-phase5.md §A.2).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`)
 * so the feature stays in lock-step with the backend contract. Nothing here is
 * hand-typed `any`.
 */

export type EditionDto = components['schemas']['EditionDto'];
export type EditionDetailDto = components['schemas']['EditionDetailDto'];
export type PageEditionDto = components['schemas']['PageEditionDto'];
export type CreateEditionRequest =
  components['schemas']['CreateEditionRequest'];
export type UpdateEditionRequest =
  components['schemas']['UpdateEditionRequest'];
export type FeatureValueDto = components['schemas']['FeatureValueDto'];
export type FeatureDefinitionDto =
  components['schemas']['FeatureDefinitionDto'];

/** Spring `Pageable` request, flattened to the query-string wire format. */
export interface EditionListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Spring sort expression, e.g. `sortOrder,asc`. */
  sort?: string;
}

/**
 * Feature value kinds the editor knows how to render. The backend sends the
 * registry type as a free-form string (`FeatureDefinitionDto.type`), so unknown
 * kinds degrade to a plain text input rather than breaking the screen.
 */
export type FeatureValueType = 'BOOLEAN' | 'NUMBER' | 'STRING';

/** Normalizes the wire `type` onto a renderable input kind. */
export function featureValueType(type?: string): FeatureValueType {
  const normalized = (type ?? '').toUpperCase();
  if (normalized === 'BOOLEAN' || normalized === 'NUMBER') {
    return normalized;
  }
  return 'STRING';
}

/** Backend truthiness for BOOLEAN feature values (`"true"` / `"false"`). */
export function isFeatureTrue(value?: string): boolean {
  return (value ?? '').trim().toLowerCase() === 'true';
}
