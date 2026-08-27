import { useState } from 'react'
import { AnnouncementRegistrationForm } from './AnnouncementRegistrationForm'
import type { HousingComplexCreateResponse } from './api'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'

export function AdminDataRegistrationPage() {
  const [housingComplex, setHousingComplex] =
    useState<HousingComplexCreateResponse | null>(null)

  return (
    <section className="admin-registration-page">
      <header className="admin-registration-heading">
        <h1>관리자 페이지</h1>
        <p>단지를 먼저 저장한 뒤 해당 단지의 원공고와 공급행을 등록합니다.</p>
      </header>
      <HousingComplexRegistrationForm onCreated={setHousingComplex} />
      <AnnouncementRegistrationForm housingComplex={housingComplex} />
    </section>
  )
}
