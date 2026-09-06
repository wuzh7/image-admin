import { ModalForm, ProFormText } from '@ant-design/pro-components';
import { Checkbox, Space, Tree } from 'antd';
import { Form } from 'antd';
import type { RoleForm } from '@/api/system/role/types';
import { defaultRoleForm, useRolePermission } from './useRolePermission';

interface RolePermissionModalProps {
  open: boolean;
  roleId?: string | number;
  onCancel: () => void;
  onSuccess: () => void;
}

export default function RolePermissionModal({ open, roleId, onCancel, onSuccess }: RolePermissionModalProps) {
  const [form] = Form.useForm<RoleForm>();
  const permission = useRolePermission({ open, roleId, form, onSuccess });

  const closeModal = () => {
    onCancel();
    permission.resetPermission();
  };

  return (
    <ModalForm<RoleForm>
      title="分配权限"
      open={open}
      width={780}
      form={form}
      layout="vertical"
      initialValues={defaultRoleForm}
      modalProps={{ destroyOnHidden: true, onCancel: closeModal }}
      submitter={{ submitButtonProps: { loading: permission.loading } }}
      onOpenChange={nextOpen => !nextOpen && closeModal()}
      onFinish={permission.submitPermission}
    >
      <ProFormText name="roleId" hidden />
      <ProFormText name="roleName" label="角色名称" fieldProps={{ disabled: true }} />

      <Space style={{ marginBottom: 8 }}>
        <Checkbox
          checked={permission.menuExpandAll}
          onChange={event => permission.setMenuExpandAll(event.target.checked)}
        >
          展开/折叠
        </Checkbox>
        <Checkbox
          checked={permission.menuAllChecked}
          onChange={event => permission.checkAllMenus(event.target.checked)}
        >
          全选/全不选
        </Checkbox>
        <Checkbox
          checked={permission.menuConnect}
          onChange={event => permission.toggleMenuConnect(event.target.checked)}
        >
          父子联动
        </Checkbox>
      </Space>
      <Tree
        className="permission-tree"
        checkable
        selectable={false}
        defaultExpandAll={permission.menuExpandAll}
        key={String(permission.menuExpandAll)}
        treeData={permission.menuTreeData}
        checkedKeys={permission.menuCheckedKeys}
        checkStrictly={!permission.menuConnect}
        onCheck={(keys, info) => permission.handleMenuCheck(keys, info.node.key, info.checked)}
      />
    </ModalForm>
  );
}
