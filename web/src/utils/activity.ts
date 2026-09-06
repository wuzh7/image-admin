import type { OperationLog } from '@/api/operationLog';

export interface ActivityView {
  title: string;
  summary: string;
  failed: boolean;
}

function parseJson(value?: string): unknown {
  if (!value) return undefined;
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function resultData(operation: OperationLog): unknown {
  const result = parseJson(operation.jsonResult);
  return result && typeof result === 'object' && 'data' in result
    ? (result as { data?: unknown }).data
    : undefined;
}

function firstId(value?: string) {
  return value?.match(/\d{6,}/)?.[0];
}

function approvedCount(value?: string) {
  if (!value) return undefined;
  const objectStart = value.indexOf('{');
  const parameters = objectStart >= 0 ? parseJson(value.slice(objectStart)) : undefined;
  if (!parameters || typeof parameters !== 'object' || !('approvedIds' in parameters)) return undefined;
  const ids = (parameters as { approvedIds?: unknown }).approvedIds;
  return Array.isArray(ids) ? ids.length : undefined;
}

function genericTarget(title: string) {
  const targets: Record<string, string> = {
    '用户管理': '用户',
    '角色管理': '角色',
    '菜单管理': '菜单',
    '客户端管理': '客户端',
    '参数管理': '系统参数',
    '个人信息': '个人信息',
    '通知公告': '通知公告',
    '对象存储配置': '对象存储配置'
  };
  return targets[title] || title.replace(/管理$/, '') || '系统设置';
}

function genericVerb(businessType: number) {
  const verbs: Record<number, string> = {
    1: '新增',
    2: '修改',
    3: '删除',
    4: '授权',
    5: '导出',
    6: '导入',
    7: '强制退出',
    8: '生成',
    9: '清空'
  };
  return verbs[businessType] || '操作';
}

export function formatOperationActivity(operation: OperationLog): ActivityView {
  const actor = operation.operName || '用户';
  const failed = operation.status !== 0;
  const id = firstId(operation.operParam || operation.operUrl);
  const data = resultData(operation);
  let title: string;
  let summary: string;

  switch (operation.title) {
    case '创建图片批次': {
      const batch = data && typeof data === 'object' ? data as { id?: unknown; categoryName?: unknown } : undefined;
      const batchId = batch?.id == null ? id : String(batch.id);
      title = failed ? `${actor}创建图片批次失败` : `${actor}创建了图片上传批次`;
      summary = [batchId ? `批次 ${batchId}` : '', batch?.categoryName ? String(batch.categoryName) : '', '等待上传']
        .filter(Boolean).join(' · ');
      break;
    }
    case '图片批次上传': {
      const count = typeof data === 'number' ? data : undefined;
      title = failed ? `${actor}上传图片失败` : count == null ? `${actor}上传了图片` : `${actor}上传了 ${count} 张图片`;
      summary = [id ? `批次 ${id}` : '', '上传完成'].filter(Boolean).join(' · ');
      break;
    }
    case '启动图片检测':
      title = failed ? `${actor}启动图片检测失败` : `${actor}开始检测批次 ${id || ''}`.trim();
      summary = '图片已进入后台检测';
      break;
    case '图片人工确认': {
      const count = approvedCount(operation.operParam);
      title = failed ? `${actor}审核批次 ${id || ''} 失败`.trim() : `${actor}完成了批次 ${id || ''} 的人工审核`.trim();
      summary = count == null ? '人工审核已完成' : `${count} 张图片已进入正式图库`;
      break;
    }
    case '图片检测重试':
      title = failed ? `${actor}重新检测批次 ${id || ''} 失败`.trim() : `${actor}重新检测了批次 ${id || ''}`.trim();
      summary = '失败图片已重新进入检测队列';
      break;
    case '正式图库删除':
      title = failed ? `${actor}删除正式图库图片失败` : `${actor}删除了正式图库图片`;
      summary = [id ? `图片 ${id}` : '', '已从正式图库移除'].filter(Boolean).join(' · ');
      break;
    case '图片类目新增':
      title = failed ? `${actor}新增图片类目失败` : `${actor}新增了图片类目`;
      summary = '类目已创建';
      break;
    case '图片类目修改':
      title = failed ? `${actor}修改图片类目失败` : `${actor}修改了图片类目`;
      summary = '类目信息已更新';
      break;
    case '图片类目删除':
      title = failed ? `${actor}删除图片类目失败` : `${actor}删除了图片类目`;
      summary = '类目已删除';
      break;
    default: {
      const verb = genericVerb(operation.businessType);
      const normalizedTitle = operation.title.endsWith(verb) ? operation.title.slice(0, -verb.length) : operation.title;
      const target = genericTarget(normalizedTitle);
      title = failed ? `${actor}${verb}${target}失败` : `${actor}${verb}了${target}`;
      summary = '操作成功';
    }
  }

  if (failed) summary = operation.errorMsg || '操作未完成';
  return { title, summary, failed };
}

export function formatActivityTime(value?: string, now = new Date()) {
  if (!value) return '时间未知';
  const date = new Date(value.includes('T') ? value : value.replace(' ', 'T'));
  if (Number.isNaN(date.getTime())) return value;
  const seconds = Math.max(0, Math.floor((now.getTime() - date.getTime()) / 1000));
  if (seconds < 60) return '刚刚';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}小时前`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}天前`;
  const pad = (number: number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
