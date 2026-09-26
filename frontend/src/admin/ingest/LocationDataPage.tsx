import { LocationSummaryUpload } from './LocationSummaryUpload'

export function LocationDataPage() {
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>주소 데이터</h1>
      <p>단지 주소를 매칭하는 데 사용할 위치요약 데이터를 업로드합니다.</p></header>
    <LocationSummaryUpload />
  </section>
}
