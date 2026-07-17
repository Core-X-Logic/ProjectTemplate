import { useMemo } from 'react';
import { ChevronDown, LoaderCircle } from 'lucide-react';
import { useIntl } from 'react-intl';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useOrganizationUnitOptions } from '@/features/users/hooks';
import type { OrganizationUnitDto } from '@/features/users/types';

interface OuSelectProps {
  /** Selected organization unit ids. */
  value: number[];
  onChange: (ouIds: number[]) => void;
  disabled?: boolean;
  id?: string;
}

interface OuOption {
  id: number;
  label: string;
  depth: number;
}

/** Depth-first flatten of the (parentId-linked) OU list, for indented options. */
function flattenTree(nodes: OrganizationUnitDto[]): OuOption[] {
  const byParent = new Map<number | null, OrganizationUnitDto[]>();
  for (const node of nodes) {
    const parent = node.parentId ?? null;
    const bucket = byParent.get(parent) ?? [];
    bucket.push(node);
    byParent.set(parent, bucket);
  }

  const result: OuOption[] = [];
  const visit = (parent: number | null, depth: number) => {
    for (const node of byParent.get(parent) ?? []) {
      if (node.id === undefined) {
        continue;
      }
      result.push({
        id: node.id,
        label: node.displayName ?? node.code ?? String(node.id),
        depth,
      });
      visit(node.id, depth + 1);
    }
  };
  visit(null, 0);

  // Defensive: orphan nodes (parent not in the payload) are still selectable.
  const seen = new Set(result.map((option) => option.id));
  for (const node of nodes) {
    if (node.id !== undefined && !seen.has(node.id)) {
      result.push({
        id: node.id,
        label: node.displayName ?? node.code ?? String(node.id),
        depth: 0,
      });
    }
  }
  return result;
}

/**
 * Simple multi-select over the OU tree (`GET /api/organization-units`).
 * Selection travels as ids — the shape `AssignOuRequest.ouIds` and
 * `CreateUserRequest.organizationUnitIds` expect.
 */
export function OuSelect({ value, onChange, disabled, id }: OuSelectProps) {
  const intl = useIntl();
  const { data: nodes = [], isLoading } = useOrganizationUnitOptions();

  const options = useMemo(() => flattenTree(nodes), [nodes]);

  const toggle = (ouId: number, checked: boolean) => {
    onChange(
      checked ? [...value, ouId] : value.filter((item) => item !== ouId),
    );
  };

  const label =
    value.length > 0
      ? options
          .filter((option) => value.includes(option.id))
          .map((option) => option.label)
          .join(', ')
      : intl.formatMessage({ id: 'users.form.organizationUnitsPlaceholder' });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="outline"
          disabled={disabled}
          className="w-full justify-between font-normal"
        >
          <span className="truncate">{label}</span>
          {isLoading ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <ChevronDown className="size-4 opacity-60" />
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className="w-(--radix-dropdown-menu-trigger-width) max-h-64 overflow-y-auto"
      >
        {options.map((option) => (
          <DropdownMenuCheckboxItem
            key={option.id}
            checked={value.includes(option.id)}
            onCheckedChange={(checked) => toggle(option.id, checked === true)}
            onSelect={(event) => event.preventDefault()}
            style={{ paddingInlineStart: `${2 + option.depth * 1}rem` }}
          >
            {option.label}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
