import { DataPipelineControl } from '../ingest/DataPipelineControl'

export function AdminHome() {
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>수집·정제</h1>
      <p>실행 중인 수집의 처리량과 예상 시간을 확인하고 실패 원인을 살펴봅니다.</p></header>
    <DataPipelineControl />
  </section>
}
