import { useEffect, useState, type CSSProperties } from 'react';
import request from '@/api/request';

export default function StoreImage({ url, alt, style }: { url?: string; alt: string; style?: CSSProperties }) {
  const [source, setSource] = useState<string>();
  useEffect(() => {
    if (!url) return;
    if (url.startsWith('https://')) { setSource(url); return; }
    const controller = new AbortController();
    let objectUrl: string | undefined;
    void request<Blob>({ url: url.replace(/^\/prod-api/, ''), responseType: 'blob', signal: controller.signal })
      .then(blob => { if (!controller.signal.aborted) { objectUrl = URL.createObjectURL(blob); setSource(objectUrl); } });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [url]);
  return source ? <img src={source} alt={alt} style={style} /> : null;
}
