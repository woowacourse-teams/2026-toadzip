import { DataPipelineControl } from './DataPipelineControl'
import { PipelineHistory } from './PipelineHistory'
import styles from './IngestWorkspace.module.css'

export function IngestPage({ domain }: { domain: 'complex' | 'announcement' }) {
  return <section className={`admin-registration-page ${styles.page}`}>
    <header className="admin-registration-heading">
      <h1>{domain === 'complex' ? '단지 수집' : '공고 수집'}</h1>
      <p>{domain === 'complex' ? '단지 원천을 수집하고 주소 좌표를 준비한 뒤 단지·주택형으로 정제합니다.'
        : '원천별로 공고를 갱신하고 실행 결과를 확인합니다.'}</p>
    </header>
    <DataPipelineControl key={`control-${domain}`} domain={domain} compact />
    <PipelineHistory key={`history-${domain}`} domain={domain} compact />
  </section>
}
