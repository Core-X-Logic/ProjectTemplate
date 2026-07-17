import type { components } from '@/api/schema';

/**
 * Organization Units feature types (FRONTEND-ARCHITECTURE.md §7).
 *
 * All request/response shapes come from the generated OpenAPI schema
 * (`npm run gen:api`) so the feature stays in lockstep with the backend.
 */

/** Flat node as returned by `GET /api/organization-units`. */
export type OrganizationUnit = components['schemas']['OuDto'];

export type CreateOuRequest = components['schemas']['CreateOuRequest'];
export type UpdateOuRequest = components['schemas']['UpdateOuRequest'];
export type MoveOuRequest = components['schemas']['MoveOuRequest'];

/** Tree node built client-side from the flat list (`parentId` + `code`). */
export interface OuTreeNode {
  id: number;
  parentId: number | null;
  code: string;
  displayName: string;
  memberCount: number;
  children: OuTreeNode[];
}

/**
 * Builds the display tree from the backend's flat list.
 *
 * Parent/child linkage follows `parentId`; sibling ordering follows the
 * hierarchical `code` (e.g. `00001`, `00001.00001`). Units whose parent is
 * missing from the list degrade gracefully to roots instead of disappearing.
 */
export function buildOuTree(units: readonly OrganizationUnit[]): OuTreeNode[] {
  const nodes = new Map<number, OuTreeNode>();

  for (const unit of units) {
    if (unit.id == null) {
      continue;
    }
    nodes.set(unit.id, {
      id: unit.id,
      parentId: unit.parentId ?? null,
      code: unit.code ?? '',
      displayName: unit.displayName ?? '',
      memberCount: unit.memberCount ?? 0,
      children: [],
    });
  }

  const roots: OuTreeNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.parentId != null ? nodes.get(node.parentId) : undefined;
    if (parent && parent.id !== node.id) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }

  const byCode = (a: OuTreeNode, b: OuTreeNode) =>
    a.code.localeCompare(b.code, undefined, { numeric: true });
  const sortDeep = (list: OuTreeNode[]): void => {
    list.sort(byCode);
    for (const node of list) {
      sortDeep(node.children);
    }
  };
  sortDeep(roots);

  return roots;
}

/** Ids of `unitId` plus all of its descendants (used to forbid cyclic moves). */
export function collectSubtreeIds(
  units: readonly OrganizationUnit[],
  unitId: number,
): Set<number> {
  const childrenByParent = new Map<number, number[]>();
  for (const unit of units) {
    if (unit.id == null || unit.parentId == null) {
      continue;
    }
    const siblings = childrenByParent.get(unit.parentId) ?? [];
    siblings.push(unit.id);
    childrenByParent.set(unit.parentId, siblings);
  }

  const result = new Set<number>();
  const stack = [unitId];
  while (stack.length > 0) {
    const current = stack.pop() as number;
    if (result.has(current)) {
      continue;
    }
    result.add(current);
    for (const child of childrenByParent.get(current) ?? []) {
      stack.push(child);
    }
  }
  return result;
}
