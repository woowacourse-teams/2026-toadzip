import styles from './StoredDataTable.module.css'

export function SourceUrl({ url }: { url: unknown }) {
  if (url === null || url === undefined || url === '') {
    return <span>기록 없음</span>
  }
  if (typeof url !== 'string' || !url.trim()) {
    return <span>{typeof url === 'string' ? '기록 없음' : 'URL 형식 확인 필요'}</span>
  }
  let parsed: URL
  try {
    parsed = new URL(url.trim())
  } catch {
    return <span>URL 형식 확인 필요</span>
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return <span>URL 형식 확인 필요</span>
  }
  parsed.username = ''
  parsed.password = ''
  for (const key of [...parsed.searchParams.keys()]) {
    if (key.toLowerCase() === 'servicekey') parsed.searchParams.delete(key)
  }
  const safeUrl = parsed.href
  return <a className={styles.sourceUrl} href={safeUrl} title={safeUrl} target="_blank" rel="noreferrer">{safeUrl}</a>
}
