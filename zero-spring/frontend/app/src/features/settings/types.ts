import type { components } from '@/api/schema';

/**
 * Settings feature types (FRONTEND-ARCHITECTURE.md §7).
 *
 * The generated OpenAPI `SettingDto` only carries `name`/`value`. The editor
 * additionally consumes an optional `defaultValue` (rendered as the "reset to
 * default" hint) and `scope`, which the backend may include on the read
 * endpoints — both stay optional so the feature never drifts from the contract.
 * Never re-declare the base shape by hand — re-run `npm run gen:api` instead.
 */

/** Which settings layer a tab edits (drives endpoint + permission selection). */
export type SettingScope = 'tenant' | 'host';

/** A single configurable setting entry. */
export type SettingDto = components['schemas']['SettingDto'] & {
  /** Effective default when the tenant/host override is cleared. */
  defaultValue?: string;
  /** Originating scope, informational only. */
  scope?: string;
};

/**
 * Batch update payload item. Only `name`/`value` are persisted; clearing
 * `value` (empty string) tells the backend to drop the override so the setting
 * falls back to its default.
 */
export type SettingUpdate = Pick<SettingDto, 'name' | 'value'>;

/** `GET /api/settings/client` — the flat, client-visible bootstrap map. */
export type ClientSettings = Record<string, string>;
