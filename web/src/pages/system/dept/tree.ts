import type { DeptTreeOption, DeptVO } from '@/api/system/dept/types';
import type { MenuTreeOption } from '@/api/system/menu/types';

export function buildDeptTree(rows: DeptVO[]): DeptVO[] {
  const items = rows.map(({ children: _children, ...row }) => ({ ...row } as DeptVO));
  const byId = new Map(items.map(row => [String(row.deptId), row]));
  const roots: DeptVO[] = [];
  for (const row of items) {
    const parent = byId.get(String(row.parentId));
    if (parent && parent !== row) (parent.children ??= []).push(row);
    else roots.push(row);
  }
  return roots;
}

export function filterDeptTree(nodes: DeptVO[], name: string, status?: string): DeptVO[] {
  return nodes.flatMap(({ children, ...row }) => {
    const matchingChildren = filterDeptTree(children || [], name, status);
    if (!(row.deptName.includes(name) && (!status || row.status === status)) && !matchingChildren.length) return [];
    return [{ ...row, ...(matchingChildren.length ? { children: matchingChildren } : {}) }];
  });
}

export function disableCurrentDept(nodes: DeptTreeOption[], currentId?: string | number, parentDisabled = false): DeptTreeOption[] {
  return nodes.map(node => {
    const disabled = parentDisabled || node.disabled || (currentId != null && String(node.id) === String(currentId));
    return { ...node, disabled, children: disableCurrentDept(node.children || [], currentId, disabled) };
  });
}

export function togglePermissionBranch(nodes: MenuTreeOption[], selected: Array<string | number>, id: string | number, checked: boolean): string[] {
  const keys = new Set(selected.map(String));
  const visit = (items: MenuTreeOption[], withinBranch = false) => {
    for (const node of items) {
      const included = withinBranch || String(node.id) === String(id);
      if (included) checked ? keys.add(String(node.id)) : keys.delete(String(node.id));
      visit(node.children || [], included);
    }
  };
  visit(nodes);
  return [...keys];
}
