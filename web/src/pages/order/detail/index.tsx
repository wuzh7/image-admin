import { DeleteOutlined, DownloadOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Col, Form, Image, Input, InputNumber, message, Modal, Popconfirm, Row, Typography } from 'antd';
import dayjs from 'dayjs';
import { useRef, useState } from 'react';
import type { OrderDetailForm, OrderDetailQuery, OrderDetailVO } from '@/api/order/types';
import { addOrderDetail, deleteOrderDetails, getOrderDetail, listOrderDetails, updateOrderDetail } from '@/api/order';
import RowActions from '@/components/common/RowActions';
import { useTableExport } from '@/hooks/useTableExport';
import { useTableSelection } from '@/hooks/useTableSelection';
import { useUserStore } from '@/stores/userStore';
import { hasRole } from '@/utils/permission';
import { toPageQuery, toTableData } from '@/utils/wuzh';

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
    preview={false}
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
  const [saving, setSaving] = useState(false);
  const [modalTitle, setModalTitle] = useState('新增订单明细');
  const userInfo = useUserStore(state => state.userInfo);
  const isAdmin = hasRole(userInfo, ['superadmin']);
  const { exportFile, updateExportParams } = useTableExport();
  const { ids: selectedIds, handleSelectionChange, clearSelection } = useTableSelection<OrderDetailVO>(row => row.id!);

  const openAdd = () => {
    setModalTitle('新增订单明细');
    form.resetFields();
    form.setFieldsValue({ skuQuantity: 1 } as OrderDetailForm);
    setModalOpen(true);
  };

  const openEdit = async (row: OrderDetailVO) => {
    if (row.id == null) return;
    const response = await getOrderDetail(row.id);
    setModalTitle('修改订单明细');
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

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (values.id == null) {
        await addOrderDetail(values);
        message.success('订单明细已新增');
      } else {
        await updateOrderDetail(values);
        message.success('订单明细已修改');
      }
      setModalOpen(false);
      actionRef.current?.reload();
    } finally {
      setSaving(false);
    }
  };

  const remove = async (ids: Array<string | number> | string | number) => {
    await deleteOrderDetails(ids);
    message.success('订单明细已删除');
    clearSelection();
    actionRef.current?.reloadAndRest?.();
  };

  const columns: ProColumns<OrderDetailVO>[] = [
    { title: '主订单号', dataIndex: 'parentOrderSn', width: 170, ellipsis: true },
    { title: '子订单号', dataIndex: 'orderSn', width: 170, ellipsis: true },
    { title: '店铺ID', dataIndex: 'fulfillmentMallId', width: 130, ellipsis: true },
    { title: '店铺名称', dataIndex: 'storeName', width: 160, search: false, ellipsis: true },
    { title: '店铺备注', dataIndex: 'storeRemark', width: 180, search: false, ellipsis: true },
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
    { title: '是否定制', dataIndex: 'customizedProducts', width: 120, search: false, ellipsis: true },
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
            isAdmin && { key: 'edit', label: '修改', icon: <EditOutlined />, onClick: () => void openEdit(row) },
            isAdmin && {
              key: 'delete',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              confirm: `确认删除子订单“${row.orderSn}”吗？`,
              onClick: () => row.id != null && void remove(row.id)
            }
          ]}
        />
      )
    }
  ];

  return (
    <PageContainer title="订单明细">
      <main>
        <ProTable<OrderDetailVO, OrderDetailQuery>
          actionRef={actionRef}
          rowKey="id"
          columns={columns}
          scroll={{ x: 2260 }}
          search={{ labelWidth: 90, defaultCollapsed: false }}
          pagination={{ defaultPageSize: 10, showSizeChanger: true }}
          rowSelection={{ selectedRowKeys: selectedIds, onChange: handleSelectionChange }}
          request={async params => {
            const query = updateExportParams(toPageQuery(params));
            const response = await listOrderDetails(query);
            return toTableData(response);
          }}
          toolbar={{ title: '订单明细列表' }}
          toolBarRender={() => [
            isAdmin && (
              <Button key="add" type="primary" icon={<PlusOutlined />} onClick={openAdd}>
                新增
              </Button>
            ),
            isAdmin && (
              <Popconfirm
                key="delete"
                title={`确认删除选中的 ${selectedIds.length} 条订单明细吗？`}
                onConfirm={() => void remove(selectedIds)}
              >
                <Button danger disabled={!selectedIds.length} icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            ),
            isAdmin && (
              <Button
                key="export"
                icon={<DownloadOutlined />}
                onClick={() =>
                  void exportFile('/order/details/export', () => `订单明细_${dayjs().format('YYYYMMDD_HHmmss')}.xlsx`)
                }
              >
                导出
              </Button>
            )
          ]}
        />
      </main>

      <Modal
        title={modalTitle}
        open={modalOpen}
        width={760}
        confirmLoading={saving}
        onOk={() => void submit()}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="id" hidden>
            <Input />
          </Form.Item>
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
