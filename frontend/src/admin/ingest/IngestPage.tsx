import { DataPipelineControl } from './DataPipelineControl'
import { PipelineHistory } from './PipelineHistory'
import styles from './IngestWorkspace.module.css'
import { useRef, useState } from 'react'
import complexStyles from './ComplexIngest.module.css'
import { ComplexIconButton } from './ComplexIconButton'

export function IngestPage({ domain }: { domain: 'complex' | 'announcement' }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  return <section className={`admin-registration-page ${styles.page}`}>
    <header className={`admin-registration-heading ${domain === 'complex' ? complexStyles.pageHeading : ''}`}>
      <h1>{domain === 'complex' ? '단지 수집' : '공고 수집'}</h1>
      {domain === 'complex' ? <button type="button" className={complexStyles.historyButton} onClick={() => {
        setHistoryOpen(true); dialog.current?.showModal()
      }}>실행 이력</button> : <p>원천별로 공고를 갱신하고 실행 결과를 확인합니다.</p>}
    </header>
    <DataPipelineControl key={`control-${domain}`} domain={domain} compact />
    {domain === 'complex' ? <dialog ref={dialog} className={complexStyles.historyDialog} aria-labelledby="complex-history-title"
      onClose={() => setHistoryOpen(false)}>
      <header><h2 id="complex-history-title">실행 이력</h2><ComplexIconButton icon="close" label="이력 닫기" onClick={() => dialog.current?.close()} /></header>
      {historyOpen && <PipelineHistory domain="complex" compact readOnlyComplex />}
    </dialog> : <PipelineHistory key={`history-${domain}`} domain={domain} compact />}
  </section>
}
