import { useEffect, useRef, useState } from 'react'
import type { ReviewTarget } from './documentReview'
import type { ExtractedDocument } from './pdfExtraction'
import { pickExchangeDirectory, readExchangeResult, readExchangeWorker, readExchangeProgress, writeExchangeRequest,
  type ExchangeDirectory, type ExchangeRequest, type ExchangeWorker } from './folderExchange'
import styles from './DocumentReview.module.css'
import { isRecord } from '../management/managementContract'
import { readExchangeConnection, saveExchangeConnection } from './reviewBrowserStore'
import { cancelExchangeRequest } from './folderExchange'

export function FolderExchangePanel({ target, document, sourceUrl, disabled, onResult, onBusyChange }: {
  target: ReviewTarget; document: ExtractedDocument; sourceUrl: string; disabled: boolean;
  onResult: (json: string) => void; onBusyChange: (busy: boolean) => void
}) {
  const [directory, setDirectory] = useState<ExchangeDirectory | null>(null)
  const [request, setRequest] = useState<ExchangeRequest | null>(null)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [worker, setWorker] = useState<ExchangeWorker | null>(null)
  const [connectionError, setConnectionError] = useState(''); const [connectionAttempt, setConnectionAttempt] = useState(0)
  const [retryable, setRetryable] = useState(false)
  const [previousDirectory, setPreviousDirectory] = useState<ExchangeDirectory | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const cancellationRequested = useRef(false)
  const controller = useRef<AbortController | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    let active = true
    void readExchangeConnection().then(value => { if (active) setPreviousDirectory(value) }).catch(cause => {
      if (active) setConnectionError(cause instanceof Error ? cause.message : '이전 연동 폴더를 읽지 못했습니다. 다시 선택하세요.')
    })
    return () => { active = false }
  }, [])
  useEffect(() => () => { controller.current?.abort(); if (timer.current) clearTimeout(timer.current); onBusyChange(false) }, [onBusyChange])
  useEffect(() => {
    if (!directory) return
    const selectedDirectory = directory
    const active = new AbortController()
    async function refresh() {
      try {
        const next = await readExchangeWorker(selectedDirectory)
        if (!active.signal.aborted) { setWorker(next); setConnectionError('') }
      } catch (cause) {
        if (!active.signal.aborted) { setWorker(null); setConnectionError(cause instanceof Error ? cause.message : '처리 도구 연결을 확인하지 못했습니다.') }
      }
    }
    void refresh()
    const interval = setInterval(() => { void refresh() }, 3000)
    return () => { active.abort(); clearInterval(interval) }
  }, [directory, connectionAttempt])
  function stop() { controller.current?.abort(); if (timer.current) clearTimeout(timer.current); setBusy(false); setCancelling(false); onBusyChange(false) }
  async function connectPrevious() {
    if (!previousDirectory) return
    try {
      const options = { mode: 'readwrite' as const }
      const permission = await previousDirectory.queryPermission?.(options)
      if (permission !== 'granted' && await previousDirectory.requestPermission?.(options) !== 'granted') throw new Error('이전 폴더의 파일 수정 권한을 허용하거나 연동 폴더를 다시 선택하세요.')
      setDirectory(previousDirectory); setError(''); setWorker(null)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '이전 폴더를 연결하지 못했습니다.') }
  }
  async function connect() {
    setError('')
    try {
      const next = await pickExchangeDirectory(); setWorker(null); setDirectory(next); setPreviousDirectory(next); setNotice('선택한 폴더에서 처리 도구를 실행하면 연결 상태가 표시됩니다. 요청·결과 파일은 자동 삭제하지 않습니다.')
      void saveExchangeConnection(next).catch(cause => setConnectionError(cause instanceof Error ? cause.message : '폴더를 기억하지 못했습니다. 다음에 다시 선택하세요.'))
    }
    catch (cause) {
      if (isRecord(cause) && cause.name === 'AbortError') {
        setNotice('폴더 연결을 완료하지 못했습니다. 선택 취소 또는 읽기·쓰기 권한을 확인한 뒤 연동 폴더 선택을 다시 눌러주세요.')
      } else {
        setError(cause instanceof Error ? cause.message : '폴더를 연결하지 못했습니다. Chrome·Edge에서 전용 폴더와 파일 수정 권한을 확인해 주세요.')
      }
    }
  }
  async function send() {
    if (!directory || !worker || busy || disabled) return
    const active = new AbortController(); controller.current = active
    cancellationRequested.current = false
    setRequest(null); setCancelling(false); setBusy(true); onBusyChange(true); setError(''); setRetryable(false); setNotice('요청 파일 작성 중…')
    try {
      if (!await readExchangeWorker(directory)) throw new Error('처리 도구 연결이 끊겼습니다. 같은 폴더에서 도구를 다시 실행하고 연결을 확인하세요.')
      const next = await writeExchangeRequest(directory, target, document, sourceUrl)
      if (active.signal.aborted) return
      setRequest(next); setNotice('요청 파일을 전달했습니다. 처리 도구의 접수를 기다립니다. 기존 수정안이 있으면 결과와 비교한 뒤 반영할 수 있습니다.')
      const deadline = Date.now() + 10 * 60 * 1000
      const poll = async () => {
        if (active.signal.aborted) return
        try {
          if (Date.now() >= deadline) throw new Error('결과 대기 10분이 지났습니다. 처리 도구 상태를 확인하거나 결과 파일을 직접 가져오세요.')
          const result = await readExchangeResult(directory, next, target, document)
          if (active.signal.aborted) return
          if (result !== null) {
            if (cancellationRequested.current) throw new Error('취소 요청 전에 결과가 발행됐습니다. 자동 가져오기를 멈췄으며 필요한 결과는 파일로 직접 확인하세요.')
            onResult(result); stop(); setNotice('결과 파일의 요청·대상·버전·PDF·인용을 검증해 가져왔습니다. 원문에서 확인한 항목만 선택하세요.'); return
          }
          const connected = await readExchangeWorker(directory)
          const progress = connected ? await readExchangeProgress(directory, next, connected) : null
          if (active.signal.aborted) return
          if (progress === 'interrupted') throw new Error('이 요청을 처리하던 도구가 중단되거나 바뀌었습니다. 새 요청으로 다시 시도하세요. 이전 파일은 보존됩니다.')
          setNotice(cancellationRequested.current ? '처리 취소 확인 중입니다. 처리 도구의 실행 종료 응답을 기다립니다.' : progress === 'processing' ? '처리 도구가 요청을 접수해 Codex로 처리 중입니다. 결과는 원문 확인 전 DB에 반영되지 않습니다.'
            : connected ? '요청 전달 완료 · 처리 도구 접수 대기 중입니다. 다른 요청 처리 중일 수 있습니다.'
              : '처리 도구 연결이 끊겼습니다. 같은 폴더에서 다시 실행하세요. 늦게 도착하는 결과는 계속 기다리며, 중지 후 새 요청을 만들 수도 있습니다.')
          timer.current = setTimeout(() => { void poll() }, 1500)
        } catch (cause) { if (!active.signal.aborted) { stop(); setRetryable(true); setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.') } }
      }
      await poll()
    } catch (cause) { if (!active.signal.aborted) { stop(); setRetryable(true); setError(cause instanceof Error ? cause.message : '요청을 전달하지 못했습니다.') } }
  }
  async function cancelProcessing() {
    if (!directory || !request || cancelling) return
    setCancelling(true); cancellationRequested.current = true
    const active = controller.current
    try {
      await cancelExchangeRequest(directory, request)
      if (active?.signal.aborted) return
      setNotice('처리 취소를 요청했습니다. 처리 도구가 실행 종료를 확인할 때까지 기다립니다. 폴더 연결과 처리 도구를 유지하세요.')
    } catch (cause) { if (active?.signal.aborted) return; setCancelling(false); cancellationRequested.current = false; setError(cause instanceof Error ? cause.message : '취소 요청을 전달하지 못했습니다. 결과 대기만 중지할 수 있습니다.') }
  }
  function copyCommand() {
    if (!navigator.clipboard || !worker?.command) { setError('클립보드를 사용할 수 없습니다. 표시된 명령을 직접 복사하세요.'); return }
    void navigator.clipboard.writeText(worker.command).then(() => setNotice('이 폴더의 처리 도구 시작 명령을 복사했습니다.')).catch(() => setError('명령을 복사하지 못했습니다. 표시된 명령을 직접 복사하세요.'))
  }
  return <details className={styles.card}><summary>로컬 폴더로 에이전트 자동 연결</summary>
    <p>선택한 전용 폴더로 PDF에서 추출한 자료와 현재값을 전달합니다. 로컬 처리 도구가 반환한 결과를 자동 검증해 가져옵니다. 원본 확인·DB 저장은 관리자가 수행합니다.</p>
    <p className={styles.hint}>선택한 폴더와 처리 도구의 폴더가 같아야 합니다. 실패 후 다시 시도하면 새 요청 ID를 사용하고 이전 파일을 보존합니다.</p>
    <p className={styles.hint}>처음에는 저장소에서 <code>node scripts/admin-review-exchange.mjs --setup --watch</code>를 실행하세요. 기본 폴더와 다음 실행용 PowerShell 파일을 준비합니다.</p>
    <div className={styles.actions}><button disabled={busy || disabled} onClick={() => void connect()}>연동 폴더 선택</button>{previousDirectory && !directory ? <button disabled={busy || disabled} onClick={() => void connectPrevious()}>이전 연동 폴더 연결 · {previousDirectory.name}</button> : null}<button disabled={busy || disabled || !directory || !worker} onClick={() => void send()}>{retryable ? '새 요청으로 다시 시도' : '요청 전달·결과 대기'}</button>
      {busy && request ? <button disabled={cancelling || !worker?.cancellation} onClick={() => void cancelProcessing()}>{cancelling ? '처리 취소 확인 중' : '요청 처리 취소'}</button> : null}
      {busy ? <button onClick={() => { stop(); setRetryable(true); setNotice('결과 대기를 중지했습니다. 요청 파일과 진행 중인 로컬 처리는 유지됩니다. 새 요청은 기존 처리의 종료를 확인한 뒤 보내세요.') }}>결과 대기 중지</button> : null}</div>
    {directory ? <div><p>연결 폴더: {directory.name}</p><p role="status">{worker ? '처리 도구 연결됨' : '처리 도구 연결이 확인되지 않았습니다. 선택한 폴더에서 최신 처리 도구를 실행하세요.'}</p>
      <code>{worker?.command ?? 'node scripts/admin-review-exchange.mjs <선택한 폴더의 전체 경로> --watch'}</code>
      {worker?.command ? <button onClick={copyCommand}>이 폴더 시작 명령 복사</button> : null}
      <button disabled={busy || disabled} onClick={() => setConnectionAttempt(value => value + 1)}>처리 도구 연결 다시 확인</button></div> : null}
    {request ? <p className={styles.hint}>요청: {request.requestFile}<br />결과: {request.resultFile}</p> : null}
    {connectionError ? <p role="alert">{connectionError}</p> : null}
    {error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
  </details>
}
