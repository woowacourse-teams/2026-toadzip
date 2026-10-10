import { agencyOptions, rentalTypeOptions } from './registrationOptions'
import { Link } from 'react-router'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { labels } from '../management/fields'
import { useState, type FormEvent } from 'react'
import {
  createAnnouncement,
  type AnnouncementCreateRequest,
  type HousingComplexCreateResponse,
} from './api'
import {
  numberValue,
  optionalStringValue,
  toRegistrationOptions,
  registrationFailure,
  stringValue,
} from './formValues'
import {
  RegistrationError,
  RegistrationSelectField,
  RegistrationTextField,
} from './RegistrationFields'

const recruitmentTypes = toRegistrationOptions(['NEW', 'WAITLIST', 'ETC'])
const receptionMethods = toRegistrationOptions(['ONLINE', 'VISIT', 'MAIL', 'ETC'])
const supplyCategories = toRegistrationOptions(['NEW_SUPPLY', 'RESUPPLY'])

export function AnnouncementRegistrationForm({
  housingComplex,
  onSubmittingChange,
  onCreated,
  disabled = false,
}: {
  housingComplex: HousingComplexCreateResponse | null
  onSubmittingChange: (isSubmitting: boolean) => void
  onCreated?: (id: number) => void
  disabled?: boolean
}) {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [createdId, setCreatedId] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({})
  const [dirty, setDirty] = useState(false)
  useUnsavedChanges(dirty)
  const selectedRental = housingComplex?.rentalType
  const rentalOptions = selectedRental && !rentalTypeOptions.some(option => option.value === selectedRental)
    ? [{ value: selectedRental, label: labels[selectedRental] ?? selectedRental }, ...rentalTypeOptions] : rentalTypeOptions

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (housingComplex && !isSubmitting && !disabled) {
      void submit(event.currentTarget, housingComplex.housingComplexId)
    }
  }

  async function submit(form: HTMLFormElement, housingComplexId: number) {
    setIsSubmitting(true)
    onSubmittingChange(true)
    setSuccess(null)
    setError(null)
    setFieldErrors({})
    try {
      const created = await createAnnouncement(
        announcementRequest(new FormData(form), housingComplexId),
      )
      setCreatedId(created.announcementId)
      setDirty(false)
      form.reset()
      setSuccess(`${created.name} 공고를 저장했습니다.`)
      onCreated?.(created.announcementId)
    } catch (requestError) {
      const failure = registrationFailure(requestError, '공고 저장 요청을 처리하지 못했습니다.')
      setError(failure.message)
      setFieldErrors(failure.fieldErrors)
    } finally {
      setIsSubmitting(false)
      onSubmittingChange(false)
    }
  }

  return (
    <section className="registration-card" aria-labelledby="announcement-registration-title">
      <h2 id="announcement-registration-title">직접 입력</h2>
      {housingComplex ? (
        <p className="selected-complex" role="status">
          선택 단지: <strong>{housingComplex.name}</strong> · {housingComplex.roadAddress}
        </p>
      ) : (
        <p className="selected-complex-guide">연결할 단지를 검색하거나 새로 등록해 주세요.</p>
      )}
      <form className="registration-form" onChange={() => setDirty(true)} onSubmit={handleSubmit}>
        <fieldset disabled={isSubmitting || disabled}>
          <legend>공고 기본 정보</legend>
          <div className="registration-grid">
            <RegistrationTextField errors={fieldErrors} label="공고명" maxLength={255} name="name" required />
            <RegistrationSelectField
              key={`rental-${housingComplex?.housingComplexId}`}
              defaultValue={housingComplex?.rentalType ?? 'HAPPY_HOUSING'}
              errors={fieldErrors}
              label="공급 유형"
              name="rentalType"
              options={rentalOptions}
              required
            />
            <RegistrationSelectField
              defaultValue="NEW"
              errors={fieldErrors}
              label="모집 유형"
              name="recruitmentType"
              options={recruitmentTypes}
              required
            />
            <RegistrationSelectField
              key={`agency-${housingComplex?.housingComplexId}`}
              defaultValue={housingComplex?.agencyCode ?? 'LH'}
              errors={fieldErrors}
              label="공급 기관"
              name="agencyCode"
              options={agencyOptions}
              required
            />
            <RegistrationTextField errors={fieldErrors} label="게시일" name="postedDate" required type="date" />
            <RegistrationTextField
              errors={fieldErrors}
              label="접수 시작일"
              name="applicationStartDate"
              required
              type="date"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="접수 종료일"
              name="applicationEndDate"
              required
              type="date"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="당첨자 발표일"
              name="winnerAnnouncementDate"
              required
              type="date"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="공식 원문 URL"
              maxLength={255}
              name="originalUrl"
              required
              type="url"
            />
          </div>
        </fieldset>

        <fieldset disabled={isSubmitting || disabled}>
          <legend>접수처</legend>
          <div className="registration-grid">
            <RegistrationTextField
              errors={fieldErrors}
              label="접수처명"
              maxLength={255}
              name="receptionPlace.name"
              required
            />
            <RegistrationSelectField
              defaultValue="ONLINE"
              errors={fieldErrors}
              label="접수 방식"
              name="receptionPlace.method"
              options={receptionMethods}
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="접수처 주소"
              maxLength={255}
              name="receptionPlace.address"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="접수처 연락처"
              maxLength={255}
              name="receptionPlace.contact"
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="접수처 URL"
              maxLength={255}
              name="receptionPlace.url"
              type="url"
            />
          </div>
        </fieldset>

        <fieldset disabled={isSubmitting || disabled}>
          <legend>공급 정보</legend>
          <div className="registration-grid">
            <RegistrationTextField
              errors={fieldErrors}
              label="원문 단지명"
              maxLength={255}
              name="supplyRow.sourceComplexName"
              key={`name-${housingComplex?.housingComplexId}`}
              defaultValue={housingComplex?.name ?? ''}
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="원문 주택형명"
              maxLength={255}
              name="supplyRow.sourceHousingTypeName"
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="공급 PNU"
              maxLength={255}
              name="supplyRow.supplyPnu"
              key={`pnu-${housingComplex?.housingComplexId}`}
              defaultValue={housingComplex?.pnu ?? ''}
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="입주 예정 연월"
              name="supplyRow.expectedMoveInMonth"
              type="month"
            />
            <RegistrationSelectField
              defaultValue="NEW_SUPPLY"
              errors={fieldErrors}
              label="공급 구분"
              name="supplyRow.supplyCategory"
              options={supplyCategories}
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="공급세대수"
              min={0}
              name="supplyRow.totalSupplyHouseholdCount"
              required
              type="number"
            />
          </div>
        </fieldset>

        {success ? <p className="registration-message registration-success" role="status">{success}</p> : null}
        {error ? <RegistrationError fieldErrors={fieldErrors} message={error} /> : null}
        <button
          className="registration-submit"
          disabled={isSubmitting || disabled || !housingComplex}
          type="submit"
        >
          {isSubmitting ? '공고 저장 중…' : '공고 저장'}
        </button>
      </form>
      {createdId ? <Link className="admin-primary" to={`/admin/announcements/${createdId}`}>등록한 공고 상세 보기 →</Link> : null}
    </section>
  )
}

function announcementRequest(formData: FormData, housingComplexId: number): AnnouncementCreateRequest {
  return {
    housingComplexId,
    name: stringValue(formData, 'name'),
    rentalType: stringValue(formData, 'rentalType'),
    recruitmentType: stringValue(formData, 'recruitmentType'),
    agencyCode: stringValue(formData, 'agencyCode'),
    postedDate: stringValue(formData, 'postedDate'),
    applicationStartDate: stringValue(formData, 'applicationStartDate'),
    applicationEndDate: stringValue(formData, 'applicationEndDate'),
    winnerAnnouncementDate: stringValue(formData, 'winnerAnnouncementDate'),
    originalUrl: stringValue(formData, 'originalUrl'),
    receptionPlace: {
      name: stringValue(formData, 'receptionPlace.name'),
      method: stringValue(formData, 'receptionPlace.method'),
      address: optionalStringValue(formData, 'receptionPlace.address'),
      contact: stringValue(formData, 'receptionPlace.contact'),
      url: optionalStringValue(formData, 'receptionPlace.url'),
    },
    supplyRow: {
      sourceComplexName: stringValue(formData, 'supplyRow.sourceComplexName'),
      sourceHousingTypeName: stringValue(formData, 'supplyRow.sourceHousingTypeName'),
      supplyPnu: stringValue(formData, 'supplyRow.supplyPnu'),
      expectedMoveInMonth: optionalStringValue(formData, 'supplyRow.expectedMoveInMonth'),
      supplyCategory: stringValue(formData, 'supplyRow.supplyCategory'),
      totalSupplyHouseholdCount: numberValue(formData, 'supplyRow.totalSupplyHouseholdCount'),
    },
  }
}
