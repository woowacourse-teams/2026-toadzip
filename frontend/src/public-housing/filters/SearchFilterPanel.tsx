import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import {
  type PublicHousingRegionRepository,
  publicHousingRegionRepository,
} from '../api/publicHousingRegionRepository.ts'
import type {
  AgencyCodeFilter,
  ApplicationStatusFilter,
  ComplexSearchFilters,
  RecruitmentTypeFilter,
  RentalTypeFilter,
} from '../api/publicHousingRepository.ts'
import {
  districtRegionOptionsForProvince,
  type PublicHousingRegion,
  PUBLIC_HOUSING_PROVINCE_OPTIONS,
  provinceNameForRegionCode,
} from '../model/publicHousingRegion.ts'
import {
  DualRangeFilter,
  type DualRangeFilterPreset,
} from './DualRangeFilter.tsx'
import { searchFiltersSignature } from './searchFilterLocation.ts'
import styles from './SearchFilterPanel.module.css'

const RENTAL_TYPE_OPTIONS = [
  ['HAPPY_HOUSING', '행복주택'],
  ['NATIONAL_RENTAL', '국민임대'],
  ['PERMANENT_RENTAL', '영구임대'],
  ['PUBLIC_RENTAL_50Y', '50년 공공임대'],
  ['INTEGRATED_PUBLIC_RENTAL', '통합공공임대'],
  ['REDEVELOPMENT_RENTAL', '재개발임대'],
  ['ETC', '기타'],
] as const satisfies readonly (readonly [RentalTypeFilter, string])[]

const APPLICATION_STATUS_OPTIONS = [
  ['BEFORE_APPLICATION', '접수예정'],
  ['APPLYING', '접수중'],
  ['CLOSED', '접수마감'],
] as const satisfies readonly (readonly [ApplicationStatusFilter, string])[]

const AGENCY_OPTIONS = [
  ['LH', 'LH'],
  ['SH', 'SH'],
  ['GH', 'GH'],
  ['ETC', '기타 기관'],
] as const satisfies readonly (readonly [AgencyCodeFilter, string])[]

const RECRUITMENT_TYPE_OPTIONS = [
  ['NEW', '신규 모집'],
  ['WAITLIST', '예비입주자 모집'],
  ['ETC', '기타 모집'],
] as const satisfies readonly (readonly [RecruitmentTypeFilter, string])[]

const DEPOSIT_PRESETS = [
  { label: '전체', minimum: null, maximum: null },
  { label: '1억 이하', minimum: null, maximum: 100_000_000 },
  { label: '1~2억', minimum: 100_000_000, maximum: 200_000_000 },
  { label: '2~3억', minimum: 200_000_000, maximum: 300_000_000 },
  { label: '3~5억', minimum: 300_000_000, maximum: 490_000_000 },
  { label: '5억 이상', minimum: 500_000_000, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

const MONTHLY_RENT_PRESETS = [
  { label: '전체', minimum: null, maximum: null },
  { label: '10만 이하', minimum: null, maximum: 100_000 },
  { label: '10~20만', minimum: 100_000, maximum: 200_000 },
  { label: '20~30만', minimum: 200_000, maximum: 300_000 },
  { label: '30~40만', minimum: 300_000, maximum: 400_000 },
  { label: '40~60만', minimum: 400_000, maximum: 590_000 },
  { label: '60만 이상', minimum: 600_000, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

const EXCLUSIVE_AREA_PRESETS = [
  { label: '전체', minimum: null, maximum: null },
  { label: '10평 미만', minimum: null, maximum: 29.7 },
  { label: '10평대', minimum: 33, maximum: 62.7 },
  { label: '20평대', minimum: 66, maximum: 95.7 },
  { label: '30평 이상', minimum: 99, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

type FilterKind = 'announcement' | 'complex'

interface SearchFilterPanelProps {
  readonly filters: ComplexSearchFilters
  readonly kind: FilterKind
  readonly onApply: (filters: ComplexSearchFilters) => void
  readonly regionRepository?: PublicHousingRegionRepository
}

export function SearchFilterPanel({
  filters,
  kind,
  onApply,
  regionRepository = publicHousingRegionRepository,
}: SearchFilterPanelProps) {
  const [open, setOpen] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const label = kind === 'complex' ? '단지' : '공고'
  const panelId = `${kind}-search-filter-panel`
  const summaryId = `${panelId}-summary`
  const appliedCount = appliedFilterCount(filters, kind)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextFilters = filtersFromForm(event.currentTarget, kind)
    const rangeError = filterRangeError(nextFilters)
    if (rangeError !== null) {
      setErrorMessage(rangeError)
      return
    }
    setErrorMessage(null)
    onApply(nextFilters)
  }

  function reset() {
    formRef.current?.reset()
    setErrorMessage(null)
    onApply({})
  }

  return (
    <section className={styles.panel} aria-label={`${label} 검색 필터`}>
      <button
        className={styles.toggle}
        type="button"
        aria-label={open ? `${label} 필터 닫기` : `${label} 필터 열기`}
        aria-controls={panelId}
        aria-describedby={summaryId}
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{label} 필터</span>
        <span className={styles.toggleMeta}>
          <span id={summaryId}>
            {appliedCount > 0 ? `${appliedCount}개 적용` : '조건 선택'}
          </span>
          <span aria-hidden="true">{open ? '▲' : '▼'}</span>
        </span>
      </button>

      {open && (
        <form
          ref={formRef}
          key={searchFiltersSignature(filters)}
          id={panelId}
          className={styles.form}
          onSubmit={submit}
        >
          <div className={styles.fields}>
            <div className={styles.grid}>
              <RegionSelect
                defaultValue={filters.regionCode ?? ''}
                messageId={`${kind}-region-district-load-error`}
                repository={regionRepository}
              />
              <FilterCheckboxGroup
                label="임대유형"
                name="rentalTypes"
                defaultValues={filters.rentalTypes}
                options={RENTAL_TYPE_OPTIONS}
              />
              <FilterCheckboxGroup
                label="모집상태"
                name="applicationStatuses"
                defaultValues={filters.applicationStatuses}
                options={APPLICATION_STATUS_OPTIONS}
              />
              <FilterCheckboxGroup
                label="공급기관"
                name="agencyCodes"
                defaultValues={filters.agencyCodes}
                options={AGENCY_OPTIONS}
              />
              <FilterCheckboxGroup
                label="모집유형"
                name="recruitmentTypes"
                defaultValues={filters.recruitmentTypes}
                options={RECRUITMENT_TYPE_OPTIONS}
              />
            </div>

            {kind === 'complex' && (
              <div className={styles.ranges}>
                <DualRangeFilter
                  legend="임대보증금"
                  maximumName="maxDeposit"
                  minimumName="minDeposit"
                  minimum={0}
                  maximum={500_000_000}
                  step={10_000_000}
                  majorStep={100_000_000}
                  initialMinimum={filters.minDeposit ?? null}
                  initialMaximum={filters.maxDeposit ?? null}
                  formatValue={formatDeposit}
                  formatTick={formatDepositTick}
                  presets={DEPOSIT_PRESETS}
                />
                <DualRangeFilter
                  legend="월 임대료"
                  maximumName="maxMonthlyRent"
                  minimumName="minMonthlyRent"
                  minimum={0}
                  maximum={600_000}
                  step={10_000}
                  majorStep={100_000}
                  initialMinimum={filters.minMonthlyRent ?? null}
                  initialMaximum={filters.maxMonthlyRent ?? null}
                  formatValue={formatMonthlyRent}
                  formatTick={formatMonthlyRentTick}
                  presets={MONTHLY_RENT_PRESETS}
                />
                <DualRangeFilter
                  legend="전용면적"
                  maximumName="maxExclusiveArea"
                  minimumName="minExclusiveArea"
                  minimum={0}
                  maximum={132}
                  step={3.3}
                  majorStep={33}
                  initialMinimum={filters.minExclusiveArea ?? null}
                  initialMaximum={filters.maxExclusiveArea ?? null}
                  formatValue={formatExclusiveArea}
                  formatTick={formatExclusiveAreaTick}
                  presets={EXCLUSIVE_AREA_PRESETS}
                />
                <RangeFields
                  legend="준공년도"
                  minimumLabel="최소 준공년도"
                  minimumName="builtYearFrom"
                  minimumValue={filters.builtYearFrom}
                  maximumLabel="최대 준공년도"
                  maximumName="builtYearTo"
                  maximumValue={filters.builtYearTo}
                  step="1"
                  unit="년"
                  maximum={9999}
                  minimum={1}
                />
              </div>
            )}
          </div>

          {errorMessage !== null && (
            <p className={styles.error} role="alert">{errorMessage}</p>
          )}

          <div className={styles.actions}>
            <button className={styles.reset} type="button" onClick={reset}>
              초기화
            </button>
            <button className={styles.apply} type="submit">
              {label} 필터 적용
            </button>
          </div>
        </form>
      )}
    </section>
  )
}

function RegionSelect({ defaultValue, messageId, repository }: {
  readonly defaultValue: string
  readonly messageId: string
  readonly repository: PublicHousingRegionRepository
}) {
  const initialProvinceCode = provinceCodeFrom(defaultValue)
  const initialDistrictCode = defaultValue.length === 5 ? defaultValue : ''
  const [provinceCode, setProvinceCode] = useState(initialProvinceCode)
  const [districtCode, setDistrictCode] = useState(initialDistrictCode)
  const [regions, setRegions] = useState<readonly PublicHousingRegion[]>([])
  const [loadStatus, setLoadStatus] = useState<
    'idle' | 'loading' | 'ready' | 'error'
  >('idle')
  const provinceSelectRef = useRef<HTMLSelectElement>(null)
  const provinceName = provinceNameForRegionCode(provinceCode)
  const districtOptions = useMemo(() => {
    const options = districtRegionOptionsForProvince(regions, provinceCode)
    if (
      districtCode === ''
      || options.some(({ regionCode }) => regionCode === districtCode)
    ) {
      return options
    }
    const selectedRegion = regions.find(
      ({ regionCode }) => regionCode === districtCode,
    ) ?? selectedRegionFallback(districtCode, provinceName)
    return [selectedRegion, ...options]
  }, [districtCode, provinceCode, provinceName, regions])

  useEffect(() => {
    const form = provinceSelectRef.current?.form
    if (form === null || form === undefined) {
      return
    }
    const reset = () => {
      setProvinceCode(initialProvinceCode)
      setDistrictCode(initialDistrictCode)
    }
    form.addEventListener('reset', reset)
    return () => form.removeEventListener('reset', reset)
  }, [initialDistrictCode, initialProvinceCode])

  useEffect(() => {
    if (provinceCode === '' || provinceName === null) {
      setRegions([])
      setLoadStatus('idle')
      return
    }

    const controller = new AbortController()
    let active = true
    setLoadStatus('loading')
    repository.search(provinceName, controller.signal)
      .then((items) => {
        if (!active) {
          return
        }
        setRegions(items)
        setLoadStatus('ready')
      })
      .catch((error: unknown) => {
        if (!active || isAbortError(error)) {
          return
        }
        setRegions([])
        setLoadStatus('error')
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [provinceCode, provinceName, repository])

  return (
    <div className={styles.regionFields}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          ref={provinceSelectRef}
          name="provinceCode"
          value={provinceCode}
          onChange={(event) => {
            setProvinceCode(event.currentTarget.value)
            setDistrictCode('')
          }}
        >
          <option value="">전체</option>
          {PUBLIC_HOUSING_PROVINCE_OPTIONS.map(([value, optionLabel]) => (
            <option key={value} value={value}>{optionLabel}</option>
          ))}
        </select>
      </label>

      <label className={styles.field}>
        <span>시·군·구</span>
        <select
          name="districtCode"
          value={districtCode}
          disabled={provinceCode === ''}
          aria-describedby={loadStatus === 'error'
            ? messageId
            : undefined}
          onChange={(event) => setDistrictCode(event.currentTarget.value)}
        >
          <option value="">
            {provinceCode === '' ? '시·도를 먼저 선택' : '전체'}
          </option>
          {districtOptions.map(({ districtName, regionCode }) => (
            <option key={regionCode} value={regionCode}>
              {districtName ?? regionCode}
            </option>
          ))}
        </select>
      </label>

      {loadStatus === 'loading' && (
        <small className={styles.regionMessage} role="status">
          시·군·구 목록을 불러오는 중입니다.
        </small>
      )}
      {loadStatus === 'error' && (
        <small
          className={styles.regionError}
          id={messageId}
          role="alert"
        >
          시·군·구를 불러오지 못했습니다. 시·도만 적용할 수 있습니다.
        </small>
      )}
    </div>
  )
}

function provinceCodeFrom(regionCode: string) {
  const provinceCode = regionCode.slice(0, 2)
  return PUBLIC_HOUSING_PROVINCE_OPTIONS.some(
    ([value]) => value === provinceCode,
  )
    ? provinceCode
    : ''
}

function selectedRegionFallback(
  regionCode: string,
  provinceName: string | null,
): PublicHousingRegion {
  return {
    regionCode,
    provinceName: provinceName ?? '',
    districtName: `선택 지역 (${regionCode})`,
    displayName: `선택 지역 (${regionCode})`,
  }
}

function FilterCheckboxGroup({
  defaultValues = [],
  label,
  name,
  options,
}: {
  readonly defaultValues?: readonly string[]
  readonly label: string
  readonly name: string
  readonly options: readonly (readonly [string, string])[]
}) {
  const selected = new Set(defaultValues)
  return (
    <fieldset className={styles.choiceGroup}>
      <legend>{label}</legend>
      <div className={styles.choiceOptions}>
        {options.map(([value, optionLabel]) => (
          <label key={value} className={styles.choice}>
            <input
              type="checkbox"
              name={name}
              value={value}
              defaultChecked={selected.has(value)}
            />
            <span>{optionLabel}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function RangeFields({
  legend,
  maximum = undefined,
  maximumLabel,
  maximumName,
  maximumValue,
  minimum = 0,
  minimumLabel,
  minimumName,
  minimumValue,
  step,
  unit,
}: {
  readonly legend: string
  readonly maximum?: number
  readonly maximumLabel: string
  readonly maximumName: string
  readonly maximumValue: number | null | undefined
  readonly minimum?: number
  readonly minimumLabel: string
  readonly minimumName: string
  readonly minimumValue: number | null | undefined
  readonly step: string
  readonly unit: string
}) {
  const minimumUnitId = `${minimumName}-unit`
  const maximumUnitId = `${maximumName}-unit`
  return (
    <fieldset className={styles.range}>
      <legend>{legend}</legend>
      <label>
        <span>{minimumLabel}</span>
        <span className={styles.inputWithUnit}>
          <input
            type="number"
            name={minimumName}
            aria-label={minimumLabel}
            aria-describedby={minimumUnitId}
            min={minimum}
            max={maximum}
            step={step}
            defaultValue={minimumValue ?? ''}
          />
          <small id={minimumUnitId}>{unit}</small>
        </span>
      </label>
      <label>
        <span>{maximumLabel}</span>
        <span className={styles.inputWithUnit}>
          <input
            type="number"
            name={maximumName}
            aria-label={maximumLabel}
            aria-describedby={maximumUnitId}
            min={minimum}
            max={maximum}
            step={step}
            defaultValue={maximumValue ?? ''}
          />
          <small id={maximumUnitId}>{unit}</small>
        </span>
      </label>
    </fieldset>
  )
}

function filtersFromForm(
  form: HTMLFormElement,
  kind: FilterKind,
): ComplexSearchFilters {
  const data = new FormData(form)
  const provinceCode = textValue(data, 'provinceCode')
  const districtCode = textValue(data, 'districtCode')
  const regionCode = districtCode || provinceCode
  const rentalTypes = formValues<RentalTypeFilter>(data, 'rentalTypes')
  const applicationStatuses = formValues<ApplicationStatusFilter>(
    data,
    'applicationStatuses',
  )
  const agencyCodes = formValues<AgencyCodeFilter>(data, 'agencyCodes')
  const recruitmentTypes = formValues<RecruitmentTypeFilter>(
    data,
    'recruitmentTypes',
  )
  const shared: ComplexSearchFilters = {
    ...(regionCode === '' ? {} : { regionCode }),
    ...(rentalTypes.length === 0 ? {} : { rentalTypes }),
    ...(applicationStatuses.length === 0 ? {} : { applicationStatuses }),
    ...(agencyCodes.length === 0 ? {} : { agencyCodes }),
    ...(recruitmentTypes.length === 0 ? {} : { recruitmentTypes }),
  }
  if (kind === 'announcement') {
    return shared
  }
  return {
    ...shared,
    ...optionalFormNumber(data, 'minDeposit'),
    ...optionalFormNumber(data, 'maxDeposit'),
    ...optionalFormNumber(data, 'minMonthlyRent'),
    ...optionalFormNumber(data, 'maxMonthlyRent'),
    ...optionalFormNumber(data, 'minExclusiveArea'),
    ...optionalFormNumber(data, 'maxExclusiveArea'),
    ...optionalFormNumber(data, 'builtYearFrom'),
    ...optionalFormNumber(data, 'builtYearTo'),
  }
}

function filterRangeError(filters: ComplexSearchFilters) {
  const ranges = [
    [filters.minDeposit, filters.maxDeposit, '임대보증금'],
    [filters.minMonthlyRent, filters.maxMonthlyRent, '월 임대료'],
    [filters.minExclusiveArea, filters.maxExclusiveArea, '전용면적'],
    [filters.builtYearFrom, filters.builtYearTo, '준공년도'],
  ] as const
  const invalid = ranges.find(([minimum, maximum]) => (
    minimum != null
    && maximum != null
    && minimum > maximum
  ))
  return invalid === undefined
    ? null
    : `${invalid[2]} 최솟값은 최댓값보다 클 수 없습니다.`
}

function appliedFilterCount(filters: ComplexSearchFilters, kind: FilterKind) {
  const common = [
    filters.regionCode,
    filters.rentalTypes?.length,
    filters.applicationStatuses?.length,
    filters.agencyCodes?.length,
    filters.recruitmentTypes?.length,
  ].filter(Boolean).length
  if (kind === 'announcement') {
    return common
  }
  return common + [
    filters.minDeposit != null || filters.maxDeposit != null,
    filters.minMonthlyRent != null || filters.maxMonthlyRent != null,
    filters.minExclusiveArea != null || filters.maxExclusiveArea != null,
    filters.builtYearFrom != null || filters.builtYearTo != null,
  ].filter(Boolean).length
}

function textValue(data: FormData, name: string) {
  const value = data.get(name)
  return typeof value === 'string' ? value.trim() : ''
}

function formValues<Value extends string>(data: FormData, name: string) {
  return data.getAll(name).filter(
    (value): value is Value => typeof value === 'string',
  )
}

function optionalFormNumber(data: FormData, name: string) {
  const value = textValue(data, name)
  return value === '' ? {} : { [name]: Number(value) }
}

function formatDeposit(value: number) {
  return `${compactDecimal(value / 100_000_000)}억`
}

function formatDepositTick(value: number) {
  return value === 500_000_000
    ? '5억+'
    : value === 0
      ? '0'
      : formatDeposit(value)
}

function formatMonthlyRent(value: number) {
  return `${compactDecimal(value / 10_000)}만 원`
}

function formatMonthlyRentTick(value: number) {
  return value === 600_000
    ? '60만+'
    : value === 0
      ? '0'
      : `${compactDecimal(value / 10_000)}만`
}

function formatExclusiveArea(value: number) {
  return `${compactDecimal(value / 3.3)}평`
}

function formatExclusiveAreaTick(value: number) {
  return value === 132
    ? '40평+'
    : value === 0
      ? '0'
      : formatExclusiveArea(value)
}

function compactDecimal(value: number) {
  return String(Number(value.toFixed(1)))
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
