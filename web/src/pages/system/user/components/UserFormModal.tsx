import type { FormInstance } from 'antd';
import { ModalForm, ProFormSelect, ProFormSwitch, ProFormText, ProFormTextArea, ProFormTreeSelect } from '@ant-design/pro-components';
import { Form } from 'antd';
import type { RoleVO } from '@/api/system/role/types';
import type { UserForm } from '@/api/system/user/types';
import type { DeptTreeOption } from '@/api/system/dept/types';

interface UserFormModalProps {
  open: boolean;
  title: string;
  form: FormInstance<UserForm>;
  initialValues: UserForm;
  currentUserId?: string | number;
  canConfigureImages: boolean;
  roleOptions: RoleVO[];
  deptOptions: DeptTreeOption[];
  onDeptChange: (deptId?: string | number) => Promise<void>;
  statusOptions: Array<{ label: string; value: string }>;
  onClose: () => void;
  onFinish: (values: UserForm) => Promise<boolean>;
}

export default function UserFormModal({
  open,
  title,
  form,
  initialValues,
  currentUserId,
  canConfigureImages,
  roleOptions,
  deptOptions,
  onDeptChange,
  statusOptions,
  onClose,
  onFinish
}: UserFormModalProps) {
  const editingUserId = Form.useWatch('userId', form);
  const editingSelf = !!editingUserId && String(editingUserId) === String(currentUserId);

  return (
    <ModalForm<UserForm>
      title={title}
      open={open}
      width={720}
      form={form}
      layout="vertical"
      initialValues={initialValues}
      modalProps={{
        destroyOnHidden: true,
        onCancel: () => {
          onClose();
          form.resetFields();
        }
      }}
      onOpenChange={nextOpen => !nextOpen && onClose()}
      onFinish={onFinish}
    >
      <ProFormText name="userId" hidden />
      <div className="form-grid">
        <>
            <ProFormText
              name="userName"
              label="用户账号"
              fieldProps={{ maxLength: 30 }}
              placeholder="请输入登录账号"
              rules={[
                { required: true, message: '用户账号不能为空' },
                { min: 2, max: 30, message: '用户账号长度必须介于 2 和 30 之间' }
              ]}
            />
            <ProFormText
              name="nickName"
              label="用户名称"
              fieldProps={{ maxLength: 30 }}
              placeholder="请输入用户名称"
              rules={[
                { required: true, message: '用户名称不能为空' },
                { min: 2, max: 30, message: '用户名称长度必须介于 2 和 30 之间' }
              ]}
            />
            {!editingUserId && <ProFormText.Password
              name="password"
              label="用户密码"
              fieldProps={{ maxLength: 20 }}
              placeholder="请输入用户密码"
              rules={[
                { required: true, message: '用户密码不能为空' },
                { min: 5, max: 20, message: '用户密码长度必须介于 5 和 20 之间' },
                { pattern: /^[^<>"'|\\]+$/, message: `不能包含非法字符：< > " ' \\ |` }
              ]}
            />}
        </>
        <ProFormTreeSelect
          name="deptId"
          label="所属部门"
          fieldProps={{
            treeData: deptOptions,
            fieldNames: { label: 'label', value: 'id', children: 'children' },
            onChange: value => {
              form.setFieldValue('roleIds', []);
              void onDeptChange(value as string | number | undefined);
            }
          }}
          rules={[{ required: true, message: '所属部门不能为空' }]}
        />
        <ProFormSelect name="status" label="状态" options={statusOptions} placeholder="请选择" />
        {!editingSelf && (
          <>
            <ProFormSelect
              name="roleIds"
              label="角色"
              mode="multiple"
              allowClear
              options={roleOptions.map(item => ({
                label: item.roleName,
                value: item.roleId,
                disabled: item.status === '1'
              }))}
              placeholder="请选择"
              rules={[{ required: true, message: '用户角色不能为空' }]}
            />
          </>
        )}
      </div>
      <ProFormTextArea name="remark" label="备注" fieldProps={{ rows: 3 }} placeholder="请输入内容" />
      {canConfigureImages && <>
        <ProFormSwitch name="riskScanEnabled" label="进行侵权风险检测" />
        <ProFormSwitch name="relayAnalysisEnabled" label="调用中转站图片分析" />
        <ProFormSwitch name="renderAllRisksEnabled" label="所有风险状态的正式图库图片可渲染" />
        <ProFormSwitch name="listingAllRisksEnabled" label="所有风险状态的正式图库图片可生成上架表格" />
      </>}
    </ModalForm>
  );
}
