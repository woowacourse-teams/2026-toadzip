import { useState } from 'react'
import { Link } from 'react-router'
import type { HousingComplexCreateResponse } from './api'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'

export function HousingComplexRegistrationPage() {
  const [created, setCreated] = useState<HousingComplexCreateResponse | null>(null)
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>단지 입력</h1>
      <p>단지의 주소와 기본 정보를 등록합니다. 저장한 단지에 이어서 공고를 연결할 수 있습니다.</p></header>
    {created ? <div className="admin-next-action">
      <span><strong>{created.name}</strong> 단지 등록 완료</span>
      <Link to={`/admin/announcements?mode=direct&complexId=${created.housingComplexId}`}>이 단지에 공고 입력 →</Link>
    </div> : null}
    <HousingComplexRegistrationForm disabled={false} onCreated={setCreated} />
  </section>
}
