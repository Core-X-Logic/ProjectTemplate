import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { cn } from '@/lib/utils';
import { Checkbox } from '@/components/ui/checkbox';
import { Skeleton } from '@/components/ui/skeleton';
import type { PermissionNodeDto } from '../types';

/**
 * Loading placeholder shown while the permission catalogue resolves. Rows are
 * indented to echo the tree's parent/child rhythm; decorative (hidden from AT).
 */
export function PermissionTreeSkeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn('flex flex-col gap-2.5', className)}
    >
      <Skeleton className="h-5 w-44" />
      <Skeleton className="ms-6 h-5 w-56" />
      <Skeleton className="ms-6 h-5 w-48" />
      <Skeleton className="h-5 w-40" />
      <Skeleton className="ms-6 h-5 w-52" />
    </div>
  );
}

/**
 * Controlled checkbox tree for the permission catalogue (`/api/permissions/tree`).
 *
 * Selection semantics:
 *  - toggling a parent selects/clears the parent AND its whole subtree;
 *  - a parent renders indeterminate while only part of its subtree is selected.
 *
 * Host-only permissions never reach a tenant user (filtered server-side), so
 * the component renders exactly what the API returns.
 */

export interface PermissionTreeProps {
  nodes: PermissionNodeDto[];
  /** Selected permission names (flat, matches `RoleDetailDto.permissions`). */
  value: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  className?: string;
}

/** Node name + every descendant name, depth-first. */
function collectNames(node: PermissionNodeDto): string[] {
  const own = node.name ? [node.name] : [];
  return (node.children ?? []).reduce(
    (acc, child) => acc.concat(collectNames(child)),
    own,
  );
}

type CheckedState = boolean | 'indeterminate';

function checkedStateOf(
  node: PermissionNodeDto,
  selected: ReadonlySet<string>,
): CheckedState {
  const names = collectNames(node);
  if (names.length === 0) {
    return false;
  }
  const count = names.filter((name) => selected.has(name)).length;
  if (count === 0) {
    return false;
  }
  return count === names.length ? true : 'indeterminate';
}

interface PermissionTreeNodeProps {
  node: PermissionNodeDto;
  selected: ReadonlySet<string>;
  disabled: boolean;
  expanded: Record<string, boolean>;
  onToggleExpand: (name: string) => void;
  onToggleSelect: (node: PermissionNodeDto) => void;
}

function PermissionTreeNode({
  node,
  selected,
  disabled,
  expanded,
  onToggleExpand,
  onToggleSelect,
}: PermissionTreeNodeProps) {
  const intl = useIntl();

  const name = node.name ?? '';
  const label = node.displayName || name;
  const children = node.children ?? [];
  const hasChildren = children.length > 0;
  const isExpanded = expanded[name] ?? true;
  const checkboxId = `permission-${name}`;

  return (
    <li role="treeitem" aria-expanded={hasChildren ? isExpanded : undefined}>
      <div className="flex items-center gap-2 py-1">
        {hasChildren ? (
          <button
            type="button"
            onClick={() => onToggleExpand(name)}
            aria-label={intl.formatMessage(
              { id: 'permission.tree.toggle' },
              { name: label },
            )}
            className="flex size-5 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            {isExpanded ? (
              <ChevronDown className="size-3.5" />
            ) : (
              <ChevronRight className="size-3.5" />
            )}
          </button>
        ) : (
          <span className="size-5 shrink-0" aria-hidden="true" />
        )}

        <Checkbox
          id={checkboxId}
          size="sm"
          disabled={disabled}
          checked={checkedStateOf(node, selected)}
          onCheckedChange={() => onToggleSelect(node)}
        />
        <label
          htmlFor={checkboxId}
          className={cn(
            'cursor-pointer select-none text-sm text-foreground',
            disabled && 'cursor-not-allowed opacity-60',
          )}
        >
          {label}
        </label>
      </div>

      {hasChildren && isExpanded && (
        <ul
          role="group"
          className="ms-2.5 flex flex-col border-s border-border ps-4"
        >
          {children.map((child) => (
            <PermissionTreeNode
              key={child.name}
              node={child}
              selected={selected}
              disabled={disabled}
              expanded={expanded}
              onToggleExpand={onToggleExpand}
              onToggleSelect={onToggleSelect}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

export function PermissionTree({
  nodes,
  value,
  onChange,
  disabled = false,
  className,
}: PermissionTreeProps) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const selected = new Set(value);

  const toggleExpand = (name: string) => {
    setExpanded((prev) => ({ ...prev, [name]: !(prev[name] ?? true) }));
  };

  const toggleSelect = (node: PermissionNodeDto) => {
    const names = collectNames(node);
    if (names.length === 0) {
      return;
    }
    const allSelected = names.every((name) => selected.has(name));
    if (allSelected) {
      const remove = new Set(names);
      onChange(value.filter((name) => !remove.has(name)));
    } else {
      onChange([...new Set([...value, ...names])]);
    }
  };

  if (nodes.length === 0) {
    return (
      <p className={cn('text-sm text-muted-foreground', className)}>
        <FormattedMessage id="permission.tree.empty" />
      </p>
    );
  }

  return (
    <ul role="tree" className={cn('flex flex-col', className)}>
      {nodes.map((node) => (
        <PermissionTreeNode
          key={node.name}
          node={node}
          selected={selected}
          disabled={disabled}
          expanded={expanded}
          onToggleExpand={toggleExpand}
          onToggleSelect={toggleSelect}
        />
      ))}
    </ul>
  );
}
