import {
  type CSSProperties,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
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
import styles from './ComplexFilterToolbar.module.css'

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

const AREA_PRESETS = [
  { label: '전체', minimum: null, maximum: null },
  { label: '10평 미만', minimum: null, maximum: 29.7 },
  { label: '10평대', minimum: 33, maximum: 62.7 },
  { label: '20평대', minimum: 66, maximum: 95.7 },
  { label: '30평 이상', minimum: 99, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

type FilterTopic =
  | 'region'
  | 'rentalType'
  | 'applicationStatus'
  | 'agency'
  | 'recruitmentType'
  | 'price'
  | 'exclusiveArea'
  | 'builtYear'

const TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['applicationStatus', '모집상태'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
  ['price', '가격'],
  ['exclusiveArea', '전용면적'],
  ['builtYear', '준공년도'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

const POPOVER_WIDTHS = {
  region: 320,
  rentalType: 300,
  applicationStatus: 300,
  agency: 300,
  recruitmentType: 300,
  price: 400,
  exclusiveArea: 360,
  builtYear: 320,
} as const satisfies Record<FilterTopic, number>

const TOPIC_KEYS = {
  region: ['regionCode'],
  rentalType: ['rentalTypes'],
  applicationStatus: ['applicationStatuses'],
  agency: ['agencyCodes'],
  recruitmentType: ['recruitmentTypes'],
  price: [
    'minDeposit',
    'maxDeposit',
    'minMonthlyRent',
    'maxMonthlyRent',
  ],
  exclusiveArea: ['minExclusiveArea', 'maxExclusiveArea'],
  builtYear: ['builtYearFrom', 'builtYearTo'],
} as const satisfies Record<
  FilterTopic,
  readonly (keyof ComplexSearchFilters)[]
>

export interface ComplexFilterToolbarProps {
  readonly filters: ComplexSearchFilters
  readonly onApply: (filters: ComplexSearchFilters) => void
  readonly regionRepository?: PublicHousingRegionRepository
}

export function ComplexFilterToolbar({
  filters,
  onApply,
  regionRepository = publicHousingRegionRepository,
}: ComplexFilterToolbarProps) {
  const [openTopic, setOpenTopic] = useState<FilterTopic | null>(null)
  const [rovingTopic, setRovingTopic] = useState<FilterTopic>('region')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [popoverPlacement, setPopoverPlacement] = useState<{
    readonly anchorX: number
    readonly left: number
    readonly width: number
  }>({
    anchorX: 0,
    left: 0,
    width: POPOVER_WIDTHS.region,
  })
  const [resolvedRegionSummary, setResolvedRegionSummary] = useState<{
    readonly label: string
    readonly regionCode: string
  } | null>(null)
  const rootRef = useRef<HTMLElement>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const triggerRefs = useRef<Partial<Record<FilterTopic, HTMLButtonElement>>>(
    {},
  )

  useEffect(() => {
    if (openTopic === null) {
      return
    }

    const closeFromOutside = (event: PointerEvent) => {
      if (
        event.target instanceof Node
        && !rootRef.current?.contains(event.target)
      ) {
        setOpenTopic(null)
        setErrorMessage(null)
      }
    }
    const closeFromEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') {
        return
      }
      event.preventDefault()
      const trigger = triggerRefs.current[openTopic]
      setOpenTopic(null)
      setErrorMessage(null)
      trigger?.focus()
    }

    document.addEventListener('pointerdown', closeFromOutside, true)
    document.addEventListener('keydown', closeFromEscape)
    return () => {
      document.removeEventListener('pointerdown', closeFromOutside, true)
      document.removeEventListener('keydown', closeFromEscape)
    }
  }, [openTopic])

  useLayoutEffect(() => {
    if (openTopic === null) {
      return
    }
    const root = rootRef.current
    const scroller = scrollerRef.current
    const trigger = triggerRefs.current[openTopic]
    if (root === null || scroller === null || trigger === undefined) {
      return
    }

    const updateAnchor = () => {
      const rootRect = root.getBoundingClientRect()
      const triggerRect = trigger.getBoundingClientRect()
      const triggerCenter = triggerRect.left - rootRect.left
        + (triggerRect.width / 2)
      const requestedWidth = POPOVER_WIDTHS[openTopic]
      const availableWidth = rootRect.width > 0
        ? rootRect.width
        : requestedWidth
      const anchorX = Math.max(0, Math.min(triggerCenter, availableWidth))
      const width = Math.min(requestedWidth, availableWidth)
      const left = Math.max(
        0,
        Math.min(anchorX - (width / 2), availableWidth - width),
      )
      setPopoverPlacement({ anchorX, left, width })
    }

    updateAnchor()
    window.addEventListener('resize', updateAnchor)
    scroller.addEventListener('scroll', updateAnchor, { passive: true })
    return () => {
      window.removeEventListener('resize', updateAnchor)
      scroller.removeEventListener('scroll', updateAnchor)
    }
  }, [openTopic])

  useEffect(() => {
    const regionCode = filters.regionCode
    if (regionCode?.length !== 5) {
      return
    }
    const provinceName = provinceNameForRegionCode(regionCode)
    if (provinceName === null) {
      return
    }
    const controller = new AbortController()
    let active = true
    regionRepository.search(provinceName, controller.signal)
      .then((regions) => {
        const selectedRegion = regions.find(
          (region) => region.regionCode === regionCode,
        )
        if (active && selectedRegion !== undefined) {
          setResolvedRegionSummary({
            label: selectedRegion.displayName,
            regionCode,
          })
        }
      })
      .catch(() => undefined)
    return () => {
      active = false
      controller.abort()
    }
  }, [filters.regionCode, regionRepository])

  function moveToolbarFocus(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      return
    }
    const currentIndex = TOPICS.findIndex(
      ([topic]) => triggerRefs.current[topic] === event.target,
    )
    if (currentIndex < 0) {
      return
    }
    event.preventDefault()
    const lastIndex = TOPICS.length - 1
    const nextIndex = event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? lastIndex
        : event.key === 'ArrowRight'
          ? (currentIndex + 1) % TOPICS.length
          : (currentIndex - 1 + TOPICS.length) % TOPICS.length
    const nextTopic = TOPICS[nextIndex][0]
    setRovingTopic(nextTopic)
    triggerRefs.current[nextTopic]?.focus()
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (openTopic === null) {
      return
    }
    const draft = topicDraftFromForm(openTopic, new FormData(event.currentTarget))
    const rangeError = topicRangeError(openTopic, draft)
    if (rangeError !== null) {
      setErrorMessage(rangeError)
      return
    }
    applyAndClose(openTopic, replaceTopic(filters, openTopic, draft))
  }

  function applyAndClose(topic: FilterTopic, next: ComplexSearchFilters) {
    setErrorMessage(null)
    onApply(next)
    setOpenTopic(null)
    triggerRefs.current[topic]?.focus()
  }

  const openLabel = topicLabel(openTopic)
  const headingId = openTopic === null
    ? undefined
    : `complex-${openTopic}-filter-heading`

  return (
    <section ref={rootRef} className={styles.root}>
      <div ref={scrollerRef} className={styles.scroller}>
        <div
          className={styles.toolbar}
          role="toolbar"
          aria-label="단지 검색 필터"
          onKeyDown={moveToolbarFocus}
        >
          {TOPICS.map(([topic, label]) => {
            const expanded = topic === openTopic
            const summary = topicSummary(
              filters,
              topic,
              resolvedRegionSummary !== null
                && resolvedRegionSummary.regionCode === filters.regionCode
                ? resolvedRegionSummary.label
                : null,
            )
            const summaryId = `complex-${topic}-filter-summary`
            return (
              <button
                ref={(node) => {
                  if (node === null) {
                    delete triggerRefs.current[topic]
                  } else {
                    triggerRefs.current[topic] = node
                  }
                }}
                key={topic}
                className={styles.trigger}
                type="button"
                tabIndex={rovingTopic === topic ? 0 : -1}
                aria-controls={`complex-${topic}-filter-popover`}
                aria-describedby={summary === null ? undefined : summaryId}
                aria-expanded={expanded}
                aria-label={`${label} 필터 ${expanded ? '닫기' : '열기'}`}
                data-active={summary === null ? 'false' : 'true'}
                onFocus={() => setRovingTopic(topic)}
                onClick={() => {
                  setErrorMessage(null)
                  setOpenTopic((current) => current === topic ? null : topic)
                }}
              >
                <span className={styles.triggerText}>
                  <span>{label}</span>
                  {summary !== null && (
                    <>
                      <span className={styles.visuallyHidden} id={summaryId}>
                        적용됨: {summary}
                      </span>
                      <span className={styles.triggerSummary} aria-hidden="true">
                        {summary}
                      </span>
                    </>
                  )}
                </span>
                <span
                  className={`${styles.chevron}${
                    expanded ? ` ${styles.chevronExpanded}` : ''
                  }`}
                  aria-hidden="true"
                />
              </button>
            )
          })}
        </div>
      </div>

      {openTopic !== null && openLabel !== null && headingId !== undefined && (
        <section
          className={styles.popover}
          id={`complex-${openTopic}-filter-popover`}
          aria-labelledby={headingId}
          data-topic={openTopic}
          style={{
            '--popover-anchor-x': `${popoverPlacement.anchorX}px`,
            '--popover-left': `${popoverPlacement.left}px`,
            '--popover-width': `${popoverPlacement.width}px`,
          } as CSSProperties}
        >
          <form
            key={`${openTopic}-${searchFiltersSignature(filters)}`}
            className={styles.form}
            onSubmit={submit}
          >
            <h2 className={styles.popoverHeading} id={headingId}>
              {openLabel} 필터
            </h2>
            <div className={styles.fields}>
              <TopicFields
                filters={filters}
                regionRepository={regionRepository}
                topic={openTopic}
              />
            </div>
            {errorMessage !== null && (
              <p className={styles.error} role="alert">{errorMessage}</p>
            )}
            <div className={styles.actions}>
              <button
                className={styles.reset}
                type="button"
                aria-label={`${openLabel} 필터 초기화`}
                onClick={() => applyAndClose(
                  openTopic,
                  replaceTopic(filters, openTopic, {}),
                )}
              >초기화</button>
              <button
                className={styles.apply}
                type="submit"
                aria-label={`${openLabel} 필터 적용`}
              >적용</button>
            </div>
          </form>
        </section>
      )}
    </section>
  )
}

function TopicFields({
  filters,
  regionRepository,
  topic,
}: {
  readonly filters: ComplexSearchFilters
  readonly regionRepository: PublicHousingRegionRepository
  readonly topic: FilterTopic
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
            formatValue={formatDeposit}
            formatTick={formatDepositTick}
            presets={DEPOSIT_PRESETS}
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
            formatValue={formatMonthlyRent}
            formatTick={formatMonthlyRentTick}
            presets={MONTHLY_RENT_PRESETS}
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
  const [provinceCode, setProvinceCode] = useState(
    provinceCodeFrom(initialRegionCode),
  )
  const [districtCode, setDistrictCode] = useState(
    initialRegionCode.length === 5 ? initialRegionCode : '',
  )
  const [regions, setRegions] = useState<readonly PublicHousingRegion[]>([])
  const [loadStatus, setLoadStatus] = useState<'idle' | 'loading' | 'error'>(
    'idle',
  )
  const loadErrorId = 'complex-region-load-error'
  const provinceName = provinceNameForRegionCode(provinceCode)
  const districts = useMemo(() => {
    const options = districtRegionOptionsForProvince(regions, provinceCode)
    if (
      districtCode === ''
      || options.some(({ regionCode }) => regionCode === districtCode)
    ) {
      return options
    }
    return [
      regions.find(({ regionCode }) => regionCode === districtCode)
        ?? selectedRegionFallback(districtCode, provinceName),
      ...options,
    ]
  }, [districtCode, provinceCode, provinceName, regions])

  useEffect(() => {
    if (provinceName === null) {
      setRegions([])
      setLoadStatus('idle')
      return
    }
    const controller = new AbortController()
    let active = true
    setLoadStatus('loading')
    repository.search(provinceName, controller.signal)
      .then((items) => {
        if (active) {
          setRegions(items)
          setLoadStatus('idle')
        }
      })
      .catch((error: unknown) => {
        if (active && !isAbortError(error)) {
          setRegions([])
          setLoadStatus('error')
        }
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [provinceName, repository])

  return (
    <div className={styles.regionFields}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          name="provinceCode"
          value={provinceCode}
          onChange={(event) => {
            setProvinceCode(event.currentTarget.value)
            setDistrictCode('')
          }}
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
          onChange={(event) => setDistrictCode(event.currentTarget.value)}
        >
          <option value="">{provinceCode === '' ? '시·도를 먼저 선택' : '전체'}</option>
          {districts.map(({ districtName, regionCode }) => (
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

function topicDraftFromForm(topic: FilterTopic, data: FormData) {
  switch (topic) {
    case 'region': {
      const regionCode = textValue(data, 'districtCode')
        || textValue(data, 'provinceCode')
      return regionCode === '' ? {} : { regionCode }
    }
    case 'rentalType':
      return optionalValues('rentalTypes', formValues<RentalTypeFilter>(data, 'rentalTypes'))
    case 'applicationStatus':
      return optionalValues('applicationStatuses', formValues<ApplicationStatusFilter>(data, 'applicationStatuses'))
    case 'agency':
      return optionalValues('agencyCodes', formValues<AgencyCodeFilter>(data, 'agencyCodes'))
    case 'recruitmentType':
      return optionalValues('recruitmentTypes', formValues<RecruitmentTypeFilter>(data, 'recruitmentTypes'))
    case 'price':
      return numbersFromForm(data, TOPIC_KEYS.price)
    case 'exclusiveArea':
      return numbersFromForm(data, TOPIC_KEYS.exclusiveArea)
    case 'builtYear':
      return numbersFromForm(data, TOPIC_KEYS.builtYear)
  }
}

function replaceTopic(
  filters: ComplexSearchFilters,
  topic: FilterTopic,
  draft: ComplexSearchFilters,
) {
  const next: Record<string, unknown> = { ...filters }
  TOPIC_KEYS[topic].forEach((key) => delete next[key])
  return { ...next, ...draft } as ComplexSearchFilters
}

function topicRangeError(topic: FilterTopic, draft: ComplexSearchFilters) {
  return topic === 'builtYear'
    && draft.builtYearFrom != null
    && draft.builtYearTo != null
    && draft.builtYearFrom > draft.builtYearTo
    ? '최소 준공년도는 최대 준공년도보다 클 수 없습니다.'
    : null
}

function topicSummary(
  filters: ComplexSearchFilters,
  topic: FilterTopic,
  resolvedRegionName: string | null,
) {
  switch (topic) {
    case 'region': {
      if (!filters.regionCode) return null
      const name = provinceNameForRegionCode(filters.regionCode)
      return name === null
        ? filters.regionCode
        : filters.regionCode.length === 5
          ? resolvedRegionName ?? `${name} ${filters.regionCode}`
          : name
    }
    case 'rentalType':
      return optionsSummary(filters.rentalTypes, RENTAL_TYPE_OPTIONS)
    case 'applicationStatus':
      return optionsSummary(filters.applicationStatuses, APPLICATION_STATUS_OPTIONS)
    case 'agency':
      return optionsSummary(filters.agencyCodes, AGENCY_OPTIONS)
    case 'recruitmentType':
      return optionsSummary(filters.recruitmentTypes, RECRUITMENT_TYPE_OPTIONS)
    case 'price': {
      const deposit = rangeSummary(filters.minDeposit, filters.maxDeposit, formatDeposit)
      const rent = rangeSummary(filters.minMonthlyRent, filters.maxMonthlyRent, formatRentSummary)
      return [
        deposit === null ? null : `보증금 ${deposit}`,
        rent === null ? null : `월세 ${rent}`,
      ].filter((value): value is string => value !== null).join(' · ') || null
    }
    case 'exclusiveArea':
      return suffixedRangeSummary(
        filters.minExclusiveArea,
        filters.maxExclusiveArea,
        (value) => compact(value / 3.3),
        '평',
      )
    case 'builtYear':
      return suffixedRangeSummary(
        filters.builtYearFrom,
        filters.builtYearTo,
        String,
        '년',
      )
  }
}

function topicLabel(topic: FilterTopic | null) {
  return TOPICS.find(([candidate]) => candidate === topic)?.[1] ?? null
}

function optionsSummary(
  values: readonly string[] | undefined,
  options: readonly (readonly [string, string])[],
) {
  if (!values?.length) return null
  if (values.length > 1) return `${values.length}개 선택`
  return options.find(([value]) => value === values[0])?.[1] ?? values[0]
}

function rangeSummary(
  minimum: number | null | undefined,
  maximum: number | null | undefined,
  format: (value: number) => string,
) {
  if (minimum == null && maximum == null) return null
  if (minimum == null) return `${format(maximum as number)} 이하`
  if (maximum == null) return `${format(minimum)} 이상`
  return `${format(minimum)}~${format(maximum)}`
}

function suffixedRangeSummary(
  minimum: number | null | undefined,
  maximum: number | null | undefined,
  format: (value: number) => string,
  suffix: string,
) {
  if (minimum == null && maximum == null) return null
  if (minimum == null) return `${format(maximum as number)}${suffix} 이하`
  if (maximum == null) return `${format(minimum)}${suffix} 이상`
  return `${format(minimum)}~${format(maximum)}${suffix}`
}

function optionalValues<Value extends string>(
  key:
    | 'rentalTypes'
    | 'applicationStatuses'
    | 'agencyCodes'
    | 'recruitmentTypes',
  values: readonly Value[],
): ComplexSearchFilters {
  return values.length === 0 ? {} : { [key]: values } as ComplexSearchFilters
}

function numbersFromForm(
  data: FormData,
  names: readonly (keyof ComplexSearchFilters)[],
) {
  return Object.fromEntries(names.flatMap((name) => {
    const value = textValue(data, name)
    return value === '' ? [] : [[name, Number(value)]]
  })) as ComplexSearchFilters
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

function provinceCodeFrom(regionCode: string) {
  const code = regionCode.slice(0, 2)
  return PUBLIC_HOUSING_PROVINCE_OPTIONS.some(([value]) => value === code)
    ? code
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

function formatDeposit(value: number) {
  return `${compact(value / 100_000_000)}억`
}

function formatDepositTick(value: number) {
  return value === 500_000_000 ? '5억+' : value === 0 ? '0' : formatDeposit(value)
}

function formatMonthlyRent(value: number) {
  return `${compact(value / 10_000)}만 원`
}

function formatRentSummary(value: number) {
  return `${compact(value / 10_000)}만`
}

function formatMonthlyRentTick(value: number) {
  return value === 600_000 ? '60만+' : value === 0 ? '0' : formatRentSummary(value)
}

function formatArea(value: number) {
  return `${compact(value / 3.3)}평`
}

function formatAreaTick(value: number) {
  return value === 132 ? '40평+' : value === 0 ? '0' : formatArea(value)
}

function compact(value: number) {
  return String(Number(value.toFixed(1)))
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
