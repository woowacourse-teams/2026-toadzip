import { Link } from 'react-router'
import { LocationSummaryUpload } from './LocationSummaryUpload'

export function LocationDataPage() {
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>주소 데이터</h1>
      <p>좌표를 적재한 뒤 <Link to="/admin/ingest/complexes#complex-registration">단지 등록</Link>에서 등록 준비 상태를 확인하세요.</p></header>
    <LocationSummaryUpload />
  </section>
}
