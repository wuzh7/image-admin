import type { BaseEntity, PageQuery } from '@/api/types';

export interface OrderDetailQuery extends PageQuery {
  parentOrderSn?: string;
  orderSn?: string;
  fulfillmentMallId?: string;
  storeName?: string;
  storeRemark?: string;
  sendWarehouseName?: string;
  shippingCompanyName?: string;
}

export interface OrderDetailForm {
  id?: string | number;
  parentOrderSn: string;
  fulfillmentMallId?: string;
  estimatedAmount?: number;
  shippingSettleAmount?: number;
  salesSettleAmount?: number;
  siteName?: string;
  customizedProducts?: string;
  sendWarehouseName?: string;
  shippingCompanyName?: string;
  orderSn: string;
  goodsName?: string;
  skuQuantity: number;
  spec?: string;
  thumbUrl?: string;
}

export interface OrderDetailVO extends BaseEntity, OrderDetailForm {}
