import type { DeptTreeOption, DeptVO } from '@/api/system/dept/types';

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
