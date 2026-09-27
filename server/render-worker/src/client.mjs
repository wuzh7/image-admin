const DEFAULT_TIMEOUT_MS = 60_000;

export class JavaClient {
  constructor({ baseUrl, token, timeoutMs = DEFAULT_TIMEOUT_MS, fetchImpl = fetch }) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.fetchImpl = fetchImpl;
  }

  async request(path, options = {}) {
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      ...options,
      signal: options.signal ?? AbortSignal.timeout(this.timeoutMs),
      headers: {
        Accept: 'application/json',
        ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        'X-Render-Worker-Token': this.token,
        ...(options.headers ?? {}),
      },
    });
    const text = await response.text();
    let body;
    try {
      body = text ? JSON.parse(text) : undefined;
    } catch {
      body = text;
    }
    if (!response.ok) throw new Error(`Java API ${response.status}: ${typeof body === 'string' ? body : body?.msg ?? 'request failed'}`);
    if (body?.code != null && body.code !== 200) throw new Error(`Java API ${body.code}: ${body.msg ?? 'request failed'}`);
    return body?.data ?? body;
  }

  claim(workerId, limit) {
    return this.request('/store/internal/render-items/claim', {
      method: 'POST',
      body: JSON.stringify({ workerId, limit }),
    });
  }

  complete(item, result) {
    return this.request(`/store/internal/render-items/${encodeURIComponent(item.itemId)}/complete`, {
      method: 'POST',
      body: JSON.stringify({ workerId: item.workerId, leaseToken: item.leaseToken, result }),
    });
  }

  renew(item) {
    return this.request(`/store/internal/render-items/${encodeURIComponent(item.itemId)}/renew`, {
      method: 'POST',
      body: JSON.stringify({ workerId: item.workerId, leaseToken: item.leaseToken }),
    });
  }

  fail(item, errorMessage) {
    return this.request(`/store/internal/render-items/${encodeURIComponent(item.itemId)}/fail`, {
      method: 'POST',
      body: JSON.stringify({ workerId: item.workerId, leaseToken: item.leaseToken, errorMessage: String(errorMessage).slice(0, 2000) }),
    });
  }

  async upload(item, buffer, format) {
    const form = new FormData();
    form.set('workerId', item.workerId);
    form.set('leaseToken', item.leaseToken);
    form.set('file', new Blob([buffer], { type: format === 'jpeg' ? 'image/jpeg' : 'image/png' }), `render-${item.itemId}.${format === 'jpeg' ? 'jpg' : 'png'}`);
    return this.request(`/store/internal/render-items/${encodeURIComponent(item.itemId)}/upload`, { method: 'POST', body: form });
  }
}

export function resultOssKey(value) {
  const result = value?.result ?? value ?? {};
  return result.resultOssKey ?? result.ossKey ?? result.key;
}
