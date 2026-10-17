import { Link } from 'react-router'
import { LocationSummaryUpload } from './LocationSummaryUpload'

export function LocationDataPage() {
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>주소 데이터</h1>
      <p>단지 준비 2단계입니다. 수집한 단지의 좌표를 적재한 뒤 이 화면에서 단지 정제를 이어서 실행할 수 있습니다.</p>
      <p>단지 원천이 아직 없다면 <Link to="/admin/ingest-v2?domain=complex">단지 수집부터 시작해 주세요.</Link></p></header>
    <LocationSummaryUpload />
  </section>
}
