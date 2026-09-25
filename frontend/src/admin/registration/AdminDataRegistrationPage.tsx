import { useState } from 'react'
import { DataPipelineControl } from '../ingest/DataPipelineControl'
import { LocationSummaryUpload } from '../ingest/LocationSummaryUpload'
import { AnnouncementRegistrationForm } from './AnnouncementRegistrationForm'
import { AnnouncementImportForm } from './AnnouncementImportForm'
import type { HousingComplexCreateResponse } from './api'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'

export function AdminDataRegistrationPage() {
  const [housingComplex, setHousingComplex] =
    useState<HousingComplexCreateResponse | null>(null)
  const [isDirectAnnouncementSubmitting, setIsDirectAnnouncementSubmitting] = useState(false)
  const [isAnnouncementImportSubmitting, setIsAnnouncementImportSubmitting] = useState(false)

  return (
    <section className="admin-registration-page">
      <header className="admin-registration-heading">
        <h1>관리자 페이지</h1>
        <p>직접 입력하거나 검증된 JSON을 검토해 원공고와 하위 정보를 등록합니다.</p>
      </header>
      <DataPipelineControl />
      <LocationSummaryUpload />
      <HousingComplexRegistrationForm
        disabled={isDirectAnnouncementSubmitting || isAnnouncementImportSubmitting}
        onCreated={setHousingComplex}
      />
      <AnnouncementRegistrationForm
        housingComplex={housingComplex}
        onSubmittingChange={setIsDirectAnnouncementSubmitting}
      />
      <AnnouncementImportForm onSubmittingChange={setIsAnnouncementImportSubmitting} />
    </section>
  )
}
