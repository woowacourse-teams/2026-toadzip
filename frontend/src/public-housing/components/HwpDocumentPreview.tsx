import { useEffect, useRef, useState } from 'react'
import type { HwpRequest, HwpResponse } from './hwpPreview.worker.ts'
import styles from './HwpDocumentPreview.module.css'

type Page = { readonly page: number; readonly count: number; readonly url: string; readonly text: string }

export default function HwpDocumentPreview({ url, name }: { readonly url: string; readonly name: string }) {
  const [page, setPage] = useState<Page | null>(null)
  const [pending, setPending] = useState(true)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [zoom, setZoom] = useState('fit')
  const workerRef = useRef<Worker | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const viewport = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let imageUrl: string | undefined
    const worker = new Worker(new URL('./hwpPreview.worker.ts', import.meta.url), { type: 'module' })
    workerRef.current = worker
    const fail = () => {
      clearTimeout(timer.current)
      worker.onmessage = null
      worker.onerror = null
      worker.terminate()
      workerRef.current = null
      setPending(false)
      setFailed(true)
    }
    worker.onerror = fail
    worker.onmessage = ({ data }: MessageEvent<HwpResponse>) => {
      clearTimeout(timer.current)
      if (data.type === 'error') { fail(); return }
      if (imageUrl) URL.revokeObjectURL(imageUrl)
      // SVG stays in image context: never insert document-provided markup into the DOM.
      imageUrl = URL.createObjectURL(new Blob([data.svg], { type: 'image/svg+xml' }))
      setPage({ page: data.page, count: data.count, text: data.text, url: imageUrl })
      setPending(false)
      viewport.current?.scrollTo?.(0, 0)
    }
    timer.current = setTimeout(fail, 60_000)
    worker.postMessage({ type: 'open', url } satisfies HwpRequest)
    return () => {
      clearTimeout(timer.current)
      worker.onmessage = null
      worker.onerror = null
      worker.terminate()
      workerRef.current = null
      if (imageUrl) URL.revokeObjectURL(imageUrl)
    }
  }, [url, attempt])

  function move(next: number) {
    if (!workerRef.current) return
    setPending(true)
    timer.current = setTimeout(() => {
      workerRef.current?.terminate()
      workerRef.current = null
      setPending(false)
      setFailed(true)
    }, 30_000)
    workerRef.current.postMessage({ type: 'page', page: next } satisfies HwpRequest)
  }

  if (failed) return <div className={styles.error}>
    <p role="alert">한글 문서를 표시하지 못했습니다. 암호가 있거나 지원하지 않는 문서일 수 있습니다. 다운로드해서 확인해 주세요.</p>
    <button type="button" onClick={() => { setFailed(false); setPending(true); setPage(null); setAttempt(attempt + 1) }}>다시 시도</button>
  </div>

  return <section className={styles.document} aria-label={`${name} 미리보기`}>
    <div className={styles.controls}>
      <button type="button" disabled={pending || !page || page.page === 0} onClick={() => page && move(page.page - 1)}>이전 페이지</button>
      <span role="status">{page ? `${page.page + 1} / ${page.count} 페이지` : '한글 문서를 준비하는 중…'}</span>
      <button type="button" disabled={pending || !page || page.page + 1 === page.count} onClick={() => page && move(page.page + 1)}>다음 페이지</button>
      <label>크기 <select value={zoom} onChange={(event) => setZoom(event.target.value)}>
        <option value="fit">너비 맞춤</option><option value="100">100%</option><option value="150">150%</option><option value="200">200%</option>
      </select></label>
    </div>
    <div className={styles.viewport} ref={viewport} tabIndex={0} aria-label="한글 문서 페이지" aria-busy={pending}>
      {page && <>
        <img className={styles.page} src={page.url} alt={`${name} ${page.page + 1}페이지`}
          style={{ width: zoom === 'fit' ? '100%' : `${Number(zoom) * 7.94}px` }}
          onError={() => { clearTimeout(timer.current); workerRef.current?.terminate(); workerRef.current = null; setFailed(true) }} />
        <details className={styles.text}><summary>현재 페이지 텍스트</summary><pre>{page.text || '추출할 수 있는 텍스트가 없습니다.'}</pre></details>
      </>}
    </div>
  </section>
}
