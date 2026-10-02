import type { PublicHousingRegionRepository } from '../api/publicHousingRegionRepository.ts'
import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'
import { PUBLIC_HOUSING_PROVINCE_OPTIONS } from '../model/publicHousingRegion.ts'
import { formatHousingMoney } from '../presentation/housingMoney.ts'
import { DualRangeFilter, type DualRangeFilterPreset } from './DualRangeFilter.tsx'
import { AGENCY_OPTIONS, APPLICATION_STATUS_OPTIONS, RECRUITMENT_TYPE_OPTIONS, RENTAL_TYPE_OPTIONS } from './searchFilterOptions.ts'
import { DETAIL_FILTER_TOPICS, type FilterTopic } from './complexFilterTopics.ts'
import { formatArea, formatAreaTick, formatDepositTick, formatMonthlyRentTick } from './complexFilterPresentation.ts'
import { useRegionSelection } from './useRegionSelection.ts'
import styles from './ComplexFilterToolbar.module.css'

const DEPOSIT_PRESETS = [
  { label: '1억 이하', minimum: null, maximum: 100_000_000 },
  { label: '1~2억', minimum: 100_000_000, maximum: 200_000_000 },
  { label: '2~3억', minimum: 200_000_000, maximum: 300_000_000 },
  { label: '3~5억', minimum: 300_000_000, maximum: 490_000_000 },
  { label: '5억 이상', minimum: 500_000_000, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

const MONTHLY_RENT_PRESETS = [
  { label: '10만원 이하', minimum: null, maximum: 100_000 },
  { label: '10~20만원', minimum: 100_000, maximum: 200_000 },
  { label: '20~30만원', minimum: 200_000, maximum: 300_000 },
  { label: '30~40만원', minimum: 300_000, maximum: 400_000 },
  { label: '40~60만원', minimum: 400_000, maximum: 590_000 },
] as const satisfies readonly DualRangeFilterPreset[]

const AREA_PRESETS = [
  { label: '10평 미만', minimum: null, maximum: 29.7 },
  { label: '10평대', minimum: 33, maximum: 62.7 },
  { label: '20평대', minimum: 66, maximum: 95.7 },
  { label: '30평 이상', minimum: 99, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

export function DetailFilterFields({
  filters,
  regionRepository,
}: {
  readonly filters: ComplexSearchFilters
  readonly regionRepository: PublicHousingRegionRepository
}) {
  return (
    <div className={styles.detailFields}>
      {DETAIL_FILTER_TOPICS.map(([topic, label]) => (
        <section className={styles.detailTopic} key={topic}>
          {topic === 'region' && <h3>{label}</h3>}
          <ComplexFilterFields
            filters={filters}
            regionRepository={regionRepository}
            topic={topic}
          />
        </section>
      ))}
    </div>
  )
}

export function ComplexFilterFields({
  filters,
  regionRepository,
  topic,
  onRangeChange,
}: {
  readonly filters: ComplexSearchFilters
  readonly regionRepository: PublicHousingRegionRepository
  readonly topic: FilterTopic
  readonly onRangeChange?: (values: Readonly<Record<string, number | null>>) => void
}) {
  switch (topic) {
    case 'region':
      return (
        <RegionFields
          initialRegionCode={filters.regionCode ?? ''}
          repository={regionRepository}
        />
      )
    case 'rentalType':
      return <ChoiceGroup label="임대유형" name="rentalTypes" values={filters.rentalTypes} options={RENTAL_TYPE_OPTIONS} />
    case 'applicationStatus':
      return <ChoiceGroup label="모집상태" name="applicationStatuses" values={filters.applicationStatuses} options={APPLICATION_STATUS_OPTIONS} />
    case 'agency':
      return <ChoiceGroup label="공급기관" name="agencyCodes" values={filters.agencyCodes} options={AGENCY_OPTIONS} />
    case 'recruitmentType':
      return <ChoiceGroup label="모집유형" name="recruitmentTypes" values={filters.recruitmentTypes} options={RECRUITMENT_TYPE_OPTIONS} />
    case 'price':
      return (
        <div className={styles.rangeStack}>
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
            formatValue={formatHousingMoney}
            formatTick={formatDepositTick}
            presets={DEPOSIT_PRESETS}
            onChange={onRangeChange && ((minimum, maximum) => onRangeChange({
              minDeposit: minimum, maxDeposit: maximum,
            }))}
            preserveInitialValuesUntilChange
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
            formatValue={formatHousingMoney}
            formatTick={formatMonthlyRentTick}
            presets={MONTHLY_RENT_PRESETS}
            onChange={onRangeChange && ((minimum, maximum) => onRangeChange({
              minMonthlyRent: minimum, maxMonthlyRent: maximum,
            }))}
            preserveInitialValuesUntilChange
          />
        </div>
      )
    case 'exclusiveArea':
      return (
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
          formatValue={formatArea}
          formatTick={formatAreaTick}
          presets={AREA_PRESETS}
          onChange={onRangeChange && ((minimum, maximum) => onRangeChange({
            minExclusiveArea: minimum, maxExclusiveArea: maximum,
          }))}
          preserveInitialValuesUntilChange
        />
      )
    case 'builtYear':
      return <BuiltYearFields filters={filters} />
  }
}

function ChoiceGroup({
  label,
  name,
  options,
  values = [],
}: {
  readonly label: string
  readonly name: string
  readonly options: readonly (readonly [string, string])[]
  readonly values?: readonly string[]
}) {
  const selected = new Set(values)
  return (
    <fieldset className={styles.choiceGroup}>
      <legend>{label}</legend>
      <div className={styles.choices}>
        {options.map(([value, optionLabel]) => (
          <label key={value} className={styles.choice}>
            <input
              type="checkbox"
              name={name}
              value={value}
              defaultChecked={selected.has(value)}
            />
            <span>
              <span className={styles.choiceCheck} aria-hidden="true">✓</span>
              {optionLabel}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function RegionFields({
  initialRegionCode,
  repository,
}: {
  readonly initialRegionCode: string
  readonly repository: PublicHousingRegionRepository
}) {
  const {
    provinceCode, districtCode, districtOptions, loadStatus, provinceSelectRef,
    selectProvince, selectDistrict,
  } = useRegionSelection(initialRegionCode, repository)
  const loadErrorId = 'complex-region-load-error'

  return (
    <div className={styles.regionFields}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          ref={provinceSelectRef}
          name="provinceCode"
          value={provinceCode}
          onChange={(event) => selectProvince(event.currentTarget.value)}
        >
          <option value="">전체</option>
          {PUBLIC_HOUSING_PROVINCE_OPTIONS.map(([code, label]) => (
            <option key={code} value={code}>{label}</option>
          ))}
        </select>
      </label>
      <label className={styles.field}>
        <span>시·군·구</span>
        <select
          name="districtCode"
          value={districtCode}
          disabled={provinceCode === ''}
          aria-describedby={loadStatus === 'error' ? loadErrorId : undefined}
          onChange={(event) => selectDistrict(event.currentTarget.value)}
        >
          <option value="">{provinceCode === '' ? '시·도를 먼저 선택' : '전체'}</option>
          {districtOptions.map(({ districtName, regionCode }) => (
            <option key={regionCode} value={regionCode}>
              {districtName ?? regionCode}
            </option>
          ))}
        </select>
      </label>
      {loadStatus === 'loading' && <small role="status">시·군·구를 불러오는 중입니다.</small>}
      {loadStatus === 'error' && (
        <small
          className={styles.regionError}
          id={loadErrorId}
          role="alert"
        >
          시·군·구를 불러오지 못했습니다. 시·도만 적용할 수 있습니다.
        </small>
      )}
    </div>
  )
}

function BuiltYearFields({ filters }: {
  readonly filters: ComplexSearchFilters
}) {
  const years = builtYearOptions(
    filters.builtYearFrom,
    filters.builtYearTo,
  )
  return (
    <fieldset className={styles.yearRange}>
      <legend>준공년도</legend>
      <label>
        <span>최소 준공년도</span>
        <select
          name="builtYearFrom"
          aria-label="최소 준공년도"
          defaultValue={filters.builtYearFrom ?? ''}
        >
          <option value="">제한 없음</option>
          {years.map((year) => (
            <option key={year} value={year}>{year}년</option>
          ))}
        </select>
      </label>
      <span aria-hidden="true">~</span>
      <label>
        <span>최대 준공년도</span>
        <select
          name="builtYearTo"
          aria-label="최대 준공년도"
          defaultValue={filters.builtYearTo ?? ''}
        >
          <option value="">제한 없음</option>
          {years.map((year) => (
            <option key={year} value={year}>{year}년</option>
          ))}
        </select>
      </label>
    </fieldset>
  )
}

function builtYearOptions(
  ...selectedYears: readonly (number | null | undefined)[]
) {
  const latestYear = new Date().getFullYear() + 5
  const years = new Set(Array.from(
    { length: latestYear - 1980 + 1 },
    (_, index) => latestYear - index,
  ))
  selectedYears.forEach((year) => {
    if (
      year != null
      && Number.isInteger(year)
      && year >= 1
      && year <= 9999
    ) {
      years.add(year)
    }
  })
  return [...years].sort((left, right) => right - left)
}
