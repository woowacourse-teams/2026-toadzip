import { useEffect, useRef, useState } from 'react'
import type { ReviewTarget } from './documentReview'
import type { ExtractedDocument } from './pdfExtraction'
import { pickExchangeDirectory, readExchangeResult, writeExchangeRequest, type ExchangeDirectory, type ExchangeRequest } from './folderExchange'
import styles from './DocumentReview.module.css'

export function FolderExchangePanel({ target, document, sourceUrl, disabled, onResult, onBusyChange }: {
  target: ReviewTarget; document: ExtractedDocument; sourceUrl: string; disabled: boolean;
  onResult: (json: string) => void; onBusyChange: (busy: boolean) => void
}) {
  const [directory, setDirectory] = useState<ExchangeDirectory | null>(null)
  const [request, setRequest] = useState<ExchangeRequest | null>(null)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const controller = useRef<AbortController | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { controller.current?.abort(); if (timer.current) clearTimeout(timer.current); onBusyChange(false) }, [onBusyChange])
  function stop() { controller.current?.abort(); if (timer.current) clearTimeout(timer.current); setBusy(false); onBusyChange(false) }
  async function connect() {
    setError('')
    try { setDirectory(await pickExchangeDirectory()); setNotice('연결한 전용 폴더에서 로컬 처리 도구를 실행하세요. 요청·결과 파일은 자동 삭제하지 않습니다.') }
    catch (cause) { setError(cause instanceof Error ? cause.message : '폴더를 연결하지 못했습니다.') }
  }
  async function send() {
    if (!directory || busy || disabled) return
    const active = new AbortController(); controller.current = active
    setBusy(true); onBusyChange(true); setError(''); setNotice('요청 파일 작성 중…')
    try {
      const next = await writeExchangeRequest(directory, target, document, sourceUrl)
      if (active.signal.aborted) return
      setRequest(next); setNotice('요청을 전달했습니다. 결과를 기다립니다. 가져온 결과는 기존 수정안을 대체하며 원문 확인 선택은 해제됩니다.')
      const deadline = Date.now() + 10 * 60 * 1000
      const poll = async () => {
        if (active.signal.aborted) return
        try {
          if (Date.now() >= deadline) throw new Error('결과 대기 10분이 지났습니다. 처리 도구 상태를 확인하거나 결과 파일을 직접 가져오세요.')
          const result = await readExchangeResult(directory, next, target, document)
          if (active.signal.aborted) return
          if (result !== null) { onResult(result); stop(); setNotice('결과 파일의 요청·대상·버전·PDF·인용을 검증해 가져왔습니다. 원문에서 확인한 항목만 선택하세요.'); return }
          timer.current = setTimeout(() => { void poll() }, 1500)
        } catch (cause) { if (!active.signal.aborted) { stop(); setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.') } }
      }
      await poll()
    } catch (cause) { if (!active.signal.aborted) { stop(); setError(cause instanceof Error ? cause.message : '요청을 전달하지 못했습니다.') } }
  }
  return <details className={styles.card}><summary>로컬 폴더로 에이전트 자동 연결</summary>
    <p>선택한 전용 폴더로 PDF에서 추출한 자료와 현재값을 전달합니다. 로컬 처리 도구가 반환한 결과를 자동 검증해 가져옵니다. 원본 확인·DB 저장은 관리자가 수행합니다.</p>
    <p className={styles.hint}>현재 수정안을 보관한 뒤 요청하세요. 연결 폴더에서 Codex 처리 도구를 실행해야 결과가 돌아옵니다.</p>
    <div className={styles.actions}><button disabled={busy || disabled} onClick={() => void connect()}>연동 폴더 선택</button><button disabled={busy || disabled || !directory} onClick={() => void send()}>요청 전달·결과 대기</button>
      {busy ? <button onClick={() => { stop(); setNotice('결과 대기를 중지했습니다. 요청 파일과 진행 중인 로컬 처리는 유지됩니다.') }}>결과 대기 중지</button> : null}</div>
    {directory ? <p>연결 폴더: {directory.name}</p> : null}{request ? <p className={styles.hint}>요청: {request.requestFile}<br />결과: {request.resultFile}</p> : null}
    {error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
  </details>
}
