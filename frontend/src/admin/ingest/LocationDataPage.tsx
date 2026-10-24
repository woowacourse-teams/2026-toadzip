import { Link } from 'react-router'
import { LocationSummaryUpload } from './LocationSummaryUpload'

export function LocationDataPage() {
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>주소 데이터</h1>
      <p>좌표 적재와 정제 결과는 <Link to="/admin/ingest/complexes">단지 수집</Link>에서도 확인할 수 있습니다. 정제는 단지 수집 화면의 3단계에서 실행하세요.</p></header>
    <LocationSummaryUpload />
  </section>
}
