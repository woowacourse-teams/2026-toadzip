import { useState, type FormEvent } from 'react'
import {
  createHousingComplex,
  type HousingComplexCreateRequest,
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
const heatingTypes = options(['INDIVIDUAL', 'CENTRAL', 'DISTRICT', 'ETC'])
const buildingTypes = options(['APARTMENT', 'OFFICETEL', 'ETC'])
const corridorTypes = options(['STAIR', 'CORRIDOR', 'MIXED', 'UNKNOWN'])

export function HousingComplexRegistrationForm({
  disabled,
  onCreated,
}: {
  disabled: boolean
  onCreated: (housingComplex: HousingComplexCreateResponse) => void
}) {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({})

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (disabled) {
      return
    }
    void submit(event.currentTarget)
  }

  async function submit(form: HTMLFormElement) {
    setIsSubmitting(true)
    setSuccess(null)
    setError(null)
    setFieldErrors({})
    try {
      const created = await createHousingComplex(housingRequest(new FormData(form)))
      onCreated(created)
      form.reset()
      setSuccess(`${created.name} 단지를 저장했습니다.`)
    } catch (requestError) {
      const failure = registrationFailure(requestError, '단지 저장 요청을 처리하지 못했습니다.')
      setError(failure.message)
      setFieldErrors(failure.fieldErrors)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <section className="registration-card" aria-labelledby="housing-registration-title">
      <h2 id="housing-registration-title">단지 등록</h2>
      <form className="registration-form" onSubmit={handleSubmit}>
        <fieldset disabled={disabled}>
          <legend>기본 정보</legend>
          <div className="registration-grid">
            <RegistrationTextField errors={fieldErrors} label="단지명" maxLength={255} name="name" required />
            <RegistrationSelectField
              defaultValue="HAPPY_HOUSING"
              errors={fieldErrors}
              label="공급 유형"
              name="rentalType"
              options={rentalTypes}
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
            <RegistrationTextField
              errors={fieldErrors}
              label="준공일"
              name="completionDate"
              required
              type="date"
            />
          </div>
        </fieldset>

        <fieldset disabled={disabled}>
          <legend>주소</legend>
          <div className="registration-grid">
            <RegistrationTextField
              errors={fieldErrors}
              label="도로명주소"
              maxLength={255}
              name="address.roadAddress"
              required
            />
            <RegistrationTextField errors={fieldErrors} label="PNU" maxLength={255} name="address.pnu" required />
            <RegistrationTextField
              errors={fieldErrors}
              label="법정동 코드"
              maxLength={255}
              name="address.legalDongCode"
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="시·도 코드"
              maxLength={255}
              name="address.provinceCode"
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="시·군·구 코드"
              maxLength={255}
              name="address.cityCountyDistrictCode"
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="위도"
              max={90}
              min={-90}
              name="address.latitude"
              required
              step="0.000001"
              type="number"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="경도"
              max={180}
              min={-180}
              name="address.longitude"
              required
              step="0.000001"
              type="number"
            />
          </div>
        </fieldset>

        <fieldset disabled={disabled}>
          <legend>시설 정보</legend>
          <div className="registration-grid">
            <RegistrationTextField
              errors={fieldErrors}
              label="전체 세대수"
              min={0}
              name="totalHouseholdCount"
              required
              type="number"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="주차대수"
              min={0}
              name="totalParkingCount"
              required
              type="number"
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="최근 1년 퇴거자 수"
              min={0}
              name="moveOutCountLastYear"
              required
              type="number"
            />
            <RegistrationSelectField
              defaultValue="INDIVIDUAL"
              errors={fieldErrors}
              label="난방 유형"
              name="heatingType"
              options={heatingTypes}
              required
            />
            <RegistrationSelectField
              defaultValue="APARTMENT"
              errors={fieldErrors}
              label="건물 유형"
              name="buildingType"
              options={buildingTypes}
              required
            />
            <RegistrationSelectField
              defaultValue="STAIR"
              errors={fieldErrors}
              label="복도 유형"
              name="corridorType"
              options={corridorTypes}
              required
            />
            <RegistrationSelectField
              defaultValue="true"
              errors={fieldErrors}
              label="엘리베이터 설치"
              name="hasElevator"
              options={[
                { label: '설치', value: 'true' },
                { label: '미설치', value: 'false' },
              ]}
              required
            />
            <RegistrationTextField
              errors={fieldErrors}
              label="대표 이미지 URL"
              maxLength={255}
              name="overviewImageUrl"
              type="url"
            />
          </div>
        </fieldset>

        {success ? <p className="registration-message registration-success" role="status">{success}</p> : null}
        {error ? <RegistrationError fieldErrors={fieldErrors} message={error} /> : null}
        <button className="registration-submit" disabled={isSubmitting || disabled} type="submit">
          {isSubmitting ? '단지 저장 중…' : '단지 저장'}
        </button>
      </form>
    </section>
  )
}

function housingRequest(formData: FormData): HousingComplexCreateRequest {
  return {
    name: stringValue(formData, 'name'),
    rentalType: stringValue(formData, 'rentalType'),
    agencyCode: stringValue(formData, 'agencyCode'),
    address: {
      roadAddress: stringValue(formData, 'address.roadAddress'),
      pnu: stringValue(formData, 'address.pnu'),
      legalDongCode: stringValue(formData, 'address.legalDongCode'),
      provinceCode: stringValue(formData, 'address.provinceCode'),
      cityCountyDistrictCode: stringValue(formData, 'address.cityCountyDistrictCode'),
      latitude: numberValue(formData, 'address.latitude'),
      longitude: numberValue(formData, 'address.longitude'),
    },
    totalHouseholdCount: numberValue(formData, 'totalHouseholdCount'),
    completionDate: stringValue(formData, 'completionDate'),
    heatingType: stringValue(formData, 'heatingType'),
    buildingType: stringValue(formData, 'buildingType'),
    corridorType: stringValue(formData, 'corridorType'),
    hasElevator: stringValue(formData, 'hasElevator') === 'true',
    totalParkingCount: numberValue(formData, 'totalParkingCount'),
    overviewImageUrl: optionalStringValue(formData, 'overviewImageUrl'),
    moveOutCountLastYear: numberValue(formData, 'moveOutCountLastYear'),
  }
}
