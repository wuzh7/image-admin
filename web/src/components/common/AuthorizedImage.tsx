import { Button, Empty, Image, Progress } from 'antd';
import { useEffect, useState } from 'react';
import { getImageAccess, getImageContent } from '@/api/image';

export default function AuthorizedImage({ url, width = 96, height = 96, alt = '', preview = true }: {
  url?: string; width?: number; height?: number; alt?: string; preview?: boolean;
}) {
  const [source, setSource] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [fallbackUrl, setFallbackUrl] = useState<string>();
  const fallback = fallbackUrl === url;

  useEffect(() => {
    if (!url) return;
    let active = true;
    let objectUrl: string | undefined;
    setSource(undefined);
    setFailed(false);
    void (async () => {
      const address = fallback ? url.replace('/content?', '/preview?') : (await getImageAccess(url)).data;
      if (!active) return;
      if (address.startsWith('https://')) setSource(address);
      else {
        const blob = await getImageContent(address);
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      }
    })().catch(() => { if (active) setFailed(true); });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url, reload, fallback]);

  if (!url) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无匹配图" />;
  if (failed) return <Button size="small" onClick={() => {
    setFallbackUrl(undefined);
    setReload(value => value + 1);
  }}>预览失败，重试</Button>;
  return source ? <Image src={source} alt={alt} preview={preview} loading="lazy" onError={() => {
    if (url.includes('thumbnail=true') && !fallback) setFallbackUrl(url);
    else if (reload < 1) setReload(value => value + 1);
    else setFailed(true);
  }} width={width} height={height} style={{ objectFit: 'contain' }} />
    : <Progress type="circle" size={32} percent={60} showInfo={false} />;
}
