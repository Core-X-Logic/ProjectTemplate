import { useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  EllipsisVertical,
  FolderTree,
  Pencil,
  Plus,
  Trash2,
} from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Can } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { OuTreeNode } from '../types';

export const OU_MANAGE_PERMISSION = 'organizationunits.manage';

export interface OuNodeActions {
  onAddChild: (node: OuTreeNode) => void;
  onEdit: (node: OuTreeNode) => void;
  onMove: (node: OuTreeNode) => void;
  onDelete: (node: OuTreeNode) => void;
}

interface OuNodeProps extends OuNodeActions {
  node: OuTreeNode;
}

/**
 * A single tree row plus its (recursively rendered) children.
 *
 * Nodes are expanded by default so the hierarchy is visible at a glance; the
 * action menu (add child / edit / move / delete) renders only for holders of
 * `organizationunits.manage` (`<Can>` — UX guard, backend enforces).
 */
export function OuNode({ node, ...actions }: OuNodeProps) {
  const intl = useIntl();
  const [expanded, setExpanded] = useState(true);
  const hasChildren = node.children.length > 0;

  return (
    <li role="treeitem" aria-expanded={hasChildren ? expanded : undefined}>
      <div className="group flex items-center gap-1.5 rounded-md px-2 py-1.5 hover:bg-accent/50">
        {hasChildren ? (
          <Button
            type="button"
            variant="ghost"
            mode="icon"
            size="sm"
            className="size-6 shrink-0"
            aria-label={node.displayName}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? (
              <ChevronDown className="size-3.5" />
            ) : (
              <ChevronRight className="size-3.5" />
            )}
          </Button>
        ) : (
          <span className="size-6 shrink-0" aria-hidden="true" />
        )}

        <FolderTree className="size-4 shrink-0 text-muted-foreground" />

        <span className="text-sm font-medium text-mono">{node.displayName}</span>

        {node.code && (
          <Badge variant="outline" size="sm" className="font-mono">
            {node.code}
          </Badge>
        )}

        <Badge variant="secondary" size="sm">
          <FormattedMessage
            id="organizationUnits.members"
            values={{ count: node.memberCount }}
          />
        </Badge>

        <Can permission={OU_MANAGE_PERMISSION}>
          <div className="ms-auto">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  type="button"
                  variant="ghost"
                  mode="icon"
                  size="sm"
                  aria-label={intl.formatMessage({
                    id: 'organizationUnits.actions',
                  })}
                >
                  <EllipsisVertical className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => actions.onAddChild(node)}>
                  <Plus className="size-4" />
                  <FormattedMessage id="organizationUnits.addChild" />
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => actions.onEdit(node)}>
                  <Pencil className="size-4" />
                  <FormattedMessage id="organizationUnits.edit" />
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => actions.onMove(node)}>
                  <FolderTree className="size-4" />
                  <FormattedMessage id="organizationUnits.move" />
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  variant="destructive"
                  onSelect={() => actions.onDelete(node)}
                >
                  <Trash2 className="size-4" />
                  <FormattedMessage id="organizationUnits.delete" />
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </Can>
      </div>

      {hasChildren && expanded && (
        <ul role="group" className="ms-5 border-s border-border ps-2">
          {node.children.map((child) => (
            <OuNode key={child.id} node={child} {...actions} />
          ))}
        </ul>
      )}
    </li>
  );
}
