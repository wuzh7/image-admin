import { DownloadOutlined, EyeOutlined, UserSwitchOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Col, Form, Image, Input, InputNumber, message, Modal, Row, Select, Typography } from 'antd';
import dayjs from 'dayjs';
import { useRef, useState } from 'react';
import type { OrderDetailForm, OrderDetailQuery, OrderDetailVO } from '@/api/order/types';
import { assignOrderOwner, getOrderDetail, listOrderDetails } from '@/api/order';
import { optionSelect } from '@/api/system/user';
import RowActions from '@/components/common/RowActions';
import { useTableExport } from '@/hooks/useTableExport';
import { useUserStore } from '@/stores/userStore';
import { hasPermi, hasRole } from '@/utils/permission';
import { formatDateTimeRange, toPageQuery, toTableData } from '@/utils/wuzh';

const { Text } = Typography;

function OrderThumbnail({ url, name }: { url?: string; name?: string }) {
  if (!url) return <Text type="secondary">-</Text>;
  return <Image
    src={url}
    alt={name || '订单商品图片'}
    width={48}
    height={48}
    loading="lazy"
    decoding="async"
    fetchPriority="low"
    style={{ objectFit: 'cover' }}
  />;
}

function formatAmount(value?: number) {
  return value == null ? '-' : `$${Number(value).toFixed(2)}`;
}

export default function OrderDetailPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [form] = Form.useForm<OrderDetailForm>();
  const [modalOpen, setModalOpen] = useState(false);
  const [assignId, setAssignId] = useState<string | number>();
  const [assignUserId, setAssignUserId] = useState<string | number>();
  const [userOptions, setUserOptions] = useState<Array<{ label: string; value: string | number }>>([]);
  const userInfo = useUserStore(state => state.userInfo);
  const isAdmin = hasRole(userInfo, ['superadmin']);
  const { exportFile, updateExportParams } = useTableExport();

  const canQuery = hasPermi(userInfo, ['order:detail:query']);
  const canExport = hasPermi(userInfo, ['order:detail:export']);
  const canAssign = isAdmin && hasPermi(userInfo, ['order:detail:assign']);

  if (!hasPermi(userInfo, ['order:detail:list'])) return null;

  const openAssign = async (row: OrderDetailVO) => {
    const result = await optionSelect([]);
    setUserOptions((result.data || []).filter(user => user.userId != null).map(user => ({
      label: `${user.nickName || user.userName} (${user.userName})`, value: user.userId!
    })));
    setAssignId(row.id);
    setAssignUserId(row.ownerId);
  };

  const openEdit = async (row: OrderDetailVO) => {
    if (row.id == null) return;
    const response = await getOrderDetail(row.id);
    form.resetFields();
    form.setFieldsValue({
      ...response.data,
      estimatedAmount: response.data.estimatedAmount == null ? undefined : Number(response.data.estimatedAmount),
      shippingSettleAmount:
        response.data.shippingSettleAmount == null ? undefined : Number(response.data.shippingSettleAmount),
      salesSettleAmount:
        response.data.salesSettleAmount == null ? undefined : Number(response.data.salesSettleAmount)
    });
    setModalOpen(true);
  };

  const columns: ProColumns<OrderDetailVO>[] = [
    { title: '主订单号', dataIndex: 'parentOrderSn', width: 170, ellipsis: true },
    { title: '订单创建时间', dataIndex: 'parentOrderTimeStr', width: 170, search: false, ellipsis: true },
    { title: '订单创建时间', dataIndex: 'parentOrderTimeRange', valueType: 'dateTimeRange', hideInTable: true },
    { title: '子订单号', dataIndex: 'orderSn', width: 170, search: false, ellipsis: true },
    { title: '店铺ID', dataIndex: 'fulfillmentMallId', width: 130, ellipsis: true },
    { title: '店铺名称', dataIndex: 'storeName', width: 160, search: false, ellipsis: true },
    { title: '店铺备注', dataIndex: 'storeRemark', width: 180, ellipsis: true },
    { title: '下单工厂', dataIndex: 'sendWarehouseName', width: 150, ellipsis: true },
    { title: '物流商', dataIndex: 'shippingCompanyName', width: 130, ellipsis: true },
    {
      title: '图片',
      dataIndex: 'thumbUrl',
      width: 80,
      search: false,
      render: (_, row) => <OrderThumbnail url={row.thumbUrl} name={row.goodsName} />
    },
    { title: '商品名称', dataIndex: 'goodsName', width: 220, search: false, ellipsis: true },
    { title: 'SKU', dataIndex: 'spec', width: 180, search: false, ellipsis: true },
    { title: '数量', dataIndex: 'skuQuantity', width: 80, search: false },
    {
      title: '订单运费预计冲回',
      dataIndex: 'shippingSettleAmount',
      width: 160,
      search: false,
      render: (_, row) => formatAmount(row.shippingSettleAmount)
    },
    {
      title: '子订单预计回款',
      dataIndex: 'salesSettleAmount',
      width: 160,
      search: false,
      render: (_, row) => formatAmount(row.salesSettleAmount)
    },
    { title: '销售站点', dataIndex: 'siteName', width: 120, search: false, ellipsis: true },
    { title: '是否定制', dataIndex: 'customizedProducts', width: 120, ellipsis: true },
    {
      title: '运费',
      dataIndex: 'estimatedAmount',
      width: 100,
      search: false,
      render: (_, row) => formatAmount(row.estimatedAmount)
    },
    { title: '更新时间', dataIndex: 'updateTime', valueType: 'dateTime', width: 170, search: false },
    {
      title: '操作',
      valueType: 'option',
      fixed: 'right',
      width: 96,
      render: (_, row) => (
        <RowActions
          actions={[
            canQuery && { key: 'view', label: '查看', icon: <EyeOutlined />, onClick: () => void openEdit(row) },
            canAssign && { key: 'assign', label: '分配用户', icon: <UserSwitchOutlined />, onClick: () => void openAssign(row) }
          ]}
        />
      )
    }
  ];

  return (
    <PageContainer title="订单明细">
      <main>
        <ProTable<OrderDetailVO, OrderDetailQuery & { parentOrderTimeRange?: [string, string] }>
          actionRef={actionRef}
          rowKey="id"
          columns={columns}
          scroll={{ x: 2430 }}
          search={{ labelWidth: 90, defaultCollapsed: false }}
          pagination={{ defaultPageSize: 10, showSizeChanger: true }}
          request={async params => {
            const { parentOrderTimeRange, ...tableParams } = params;
            const range = formatDateTimeRange(parentOrderTimeRange);
            const query = updateExportParams(toPageQuery({
              ...tableParams,
              beginParentOrderTime: range?.[0],
              endParentOrderTime: range?.[1]
            }));
            const response = await listOrderDetails(query);
            return toTableData(response);
          }}
          toolbar={{ title: '订单明细列表' }}
          toolBarRender={() => canExport ? [
            <Button
              key="export"
              icon={<DownloadOutlined />}
              onClick={() =>
                void exportFile('/order/details/export', () => `订单明细_${dayjs().format('YYYYMMDD_HHmmss')}.xlsx`)
              }
            >
              导出
            </Button>
          ] : []}
        />
      </main>

      <Modal title="分配订单归属用户" open={assignId != null} okButtonProps={{ disabled: assignUserId == null }}
        onCancel={() => setAssignId(undefined)} onOk={async () => {
          if (assignId == null || assignUserId == null) return;
          await assignOrderOwner(assignId, assignUserId);
          message.success('分配成功');
          setAssignId(undefined);
          actionRef.current?.reload();
        }}>
        <Select showSearch optionFilterProp="label" style={{ width: '100%' }} options={userOptions}
          value={assignUserId} onChange={setAssignUserId} placeholder="选择用户" />
      </Modal>

      <Modal
        title="订单明细（只读）"
        open={modalOpen}
        width={760}
        footer={<Button onClick={() => setModalOpen(false)}>关闭</Button>}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false} disabled>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                label="主订单号"
                name="parentOrderSn"
                rules={[{ required: true, whitespace: true }, { max: 64 }]}
              >
                <Input placeholder="请输入主订单号" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="订单创建时间" name="parentOrderTimeStr">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="子订单号" name="orderSn" rules={[{ required: true, whitespace: true }, { max: 64 }]}>
                <Input placeholder="请输入子订单号" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="店铺ID" name="fulfillmentMallId" rules={[{ max: 64 }]}>
                <Input placeholder="请输入店铺ID" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="运费" name="estimatedAmount" rules={[{ type: 'number', min: 0 }]}>
                <InputNumber min={0} precision={2} prefix="$" style={{ width: '100%' }} placeholder="请输入运费" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="订单运费预计冲回" name="shippingSettleAmount" rules={[{ type: 'number', min: 0 }]}>
                <InputNumber min={0} precision={2} prefix="$" style={{ width: '100%' }} placeholder="请输入预计冲回" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="子订单预计回款" name="salesSettleAmount" rules={[{ type: 'number', min: 0 }]}>
                <InputNumber min={0} precision={2} prefix="$" style={{ width: '100%' }} placeholder="请输入预计回款" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="销售站点" name="siteName" rules={[{ max: 128 }]}>
                <Input placeholder="请输入销售站点" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="是否定制" name="customizedProducts">
                <Input placeholder="可空，输入内容将原样保存" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="下单工厂" name="sendWarehouseName" rules={[{ max: 128 }]}>
                <Input placeholder="请输入下单工厂" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="物流商" name="shippingCompanyName" rules={[{ max: 128 }]}>
                <Input placeholder="请输入物流商" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="数量" name="skuQuantity" rules={[{ required: true }, { type: 'number', min: 1 }]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="请输入数量" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="SKU" name="spec" rules={[{ max: 512 }]}>
                <Input placeholder="请输入SKU" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item label="商品名称" name="goodsName" rules={[{ max: 1000 }]}>
                <Input.TextArea rows={2} placeholder="请输入商品名称" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                label="图片地址"
                name="thumbUrl"
                rules={[{ max: 1000 }, { pattern: /^https?:\/\/.+/i, message: '请输入有效的 HTTP 或 HTTPS 地址' }]}
              >
                <Input placeholder="https://example.com/image.jpg" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </PageContainer>
  );
}
