import { useState, type FormEvent } from 'react'
import {
  createAnnouncement,
  type AnnouncementCreateRequest,
  type HousingComplexCreateResponse,
} from './api'
import {
  numberValue,
  optionalStringValue,
  options,
  registrationFailure,
  stringValue,
} from './formValues'
import {
  RegistrationError,
  RegistrationSelectField,
  RegistrationTextField,
} from './RegistrationFields'

const rentalTypes = options([
  'HAPPY_HOUSING',
  'NATIONAL_RENTAL',
  'PERMANENT_RENTAL',
  'PUBLIC_RENTAL_50Y',
  'INTEGRATED_PUBLIC_RENTAL',
  'REDEVELOPMENT_RENTAL',
  'ETC',
])
const agencyCodes = options(['LH', 'SH', 'GH', 'ETC'])
const recruitmentTypes = options(['NEW', 'WAITLIST', 'ETC'])
const receptionMethods = options(['ONLINE', 'VISIT', 'MAIL', 'ETC'])
const supplyCategories = options(['NEW_SUPPLY', 'RESUPPLY'])

export function AnnouncementRegistrationForm({
  housingComplex,
  onSubmittingChange,
}: {
  housingComplex: HousingComplexCreateResponse | null
  onSubmittingChange: (isSubmitting: boolean) => void
}) {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({})

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (housingComplex) {
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
      form.reset()
      setSuccess(`${created.name} 공고를 저장했습니다.`)
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
      <h2 id="announcement-registration-title">공고 등록</h2>
      {housingComplex ? (
        <p className="selected-complex" role="status">
          선택 단지: <strong>{housingComplex.name}</strong> · {housingComplex.roadAddress}
        </p>
      ) : (
        <p className="selected-complex-guide">먼저 이 페이지에서 단지를 등록해 주세요.</p>
      )}
      <form className="registration-form" onSubmit={handleSubmit}>
        <fieldset>
          <legend>공고 기본 정보</legend>
          <div className="registration-grid">
            <RegistrationTextField errors={fieldErrors} label="공고명" maxLength={255} name="name" required />
            <RegistrationSelectField
              defaultValue="HAPPY_HOUSING"
              errors={fieldErrors}
              label="공급 유형"
              name="rentalType"
              options={rentalTypes}
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
              defaultValue="LH"
              errors={fieldErrors}
              label="공급 기관"
              name="agencyCode"
              options={agencyCodes}
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

        <fieldset>
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

        <fieldset>
          <legend>단일 공급행</legend>
          <div className="registration-grid">
            <RegistrationTextField
              errors={fieldErrors}
              label="원문 단지명"
              maxLength={255}
              name="supplyRow.sourceComplexName"
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
          disabled={isSubmitting || !housingComplex}
          type="submit"
        >
          {isSubmitting ? '공고 저장 중…' : '공고 저장'}
        </button>
      </form>
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
