import {
  type CSSProperties,
  Fragment,
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
  { label: '1억 이하', minimum: null, maximum: 100_000_000 },
  { label: '1~2억', minimum: 100_000_000, maximum: 200_000_000 },
  { label: '2~3억', minimum: 200_000_000, maximum: 300_000_000 },
  { label: '3~5억', minimum: 300_000_000, maximum: 490_000_000 },
  { label: '5억 이상', minimum: 500_000_000, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

const MONTHLY_RENT_PRESETS = [
  { label: '10만 이하', minimum: null, maximum: 100_000 },
  { label: '10~20만', minimum: 100_000, maximum: 200_000 },
  { label: '20~30만', minimum: 200_000, maximum: 300_000 },
  { label: '30~40만', minimum: 300_000, maximum: 400_000 },
  { label: '40~60만', minimum: 400_000, maximum: 590_000 },
] as const satisfies readonly DualRangeFilterPreset[]

const AREA_PRESETS = [
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

const MOBILE_PRIMARY_TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['price', '가격'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

const MOBILE_SHEET_TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['price', '가격'],
  ['exclusiveArea', '전용면적'],
  ['applicationStatus', '모집상태'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
  ['builtYear', '준공년도'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

const POPOVER_WIDTHS = {
  region: 320,
  rentalType: 280,
  applicationStatus: 280,
  agency: 280,
  recruitmentType: 280,
  price: 420,
  exclusiveArea: 380,
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
  readonly resultCountLabel?: string
}

export function ComplexFilterToolbar({
  filters,
  onApply,
  regionRepository = publicHousingRegionRepository,
  resultCountLabel,
}: ComplexFilterToolbarProps) {
  const filtersSignature = searchFiltersSignature(filters)
  const [openTopic, setOpenTopic] = useState<FilterTopic | null>(null)
  const [mobileSheetOpen, setMobileSheetOpen] = useState(false)
  const [mobileDraftFilters, setMobileDraftFilters] = useState(filters)
  const [mobileDraftDirty, setMobileDraftDirty] = useState(false)
  const [mobileFormRevision, setMobileFormRevision] = useState(0)
  const [mobileInitialTopic, setMobileInitialTopic] =
    useState<FilterTopic | null>(null)
  const [rovingTopic, setRovingTopic] = useState<FilterTopic>('region')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [popoverPlacement, setPopoverPlacement] = useState<{
    readonly anchorX: number
    readonly left: number
    readonly top: number
    readonly width: number
  }>({
    anchorX: 0,
    left: 0,
    top: 0,
    width: POPOVER_WIDTHS.region,
  })
  const [resolvedRegionSummary, setResolvedRegionSummary] = useState<{
    readonly label: string
    readonly regionCode: string
  } | null>(null)
  const rootRef = useRef<HTMLElement>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const mobileSheetRef = useRef<HTMLElement>(null)
  const mobileSheetBodyRef = useRef<HTMLDivElement>(null)
  const mobileCloseRef = useRef<HTMLButtonElement>(null)
  const mobileResetRef = useRef<HTMLButtonElement>(null)
  const mobileResetFocusPendingRef = useRef(false)
  const mobileTriggerRef = useRef<HTMLButtonElement | null>(null)
  const mobileOpenedFiltersSignatureRef = useRef(filtersSignature)
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

  useEffect(() => {
    if (!mobileSheetOpen) {
      return
    }
    const opener = mobileTriggerRef.current
    const html = document.documentElement
    const body = document.body
    const previousHtmlOverflow = html.style.overflow
    const previousBodyOverflow = body.style.overflow
    const close = () => {
      setMobileSheetOpen(false)
      setMobileInitialTopic(null)
      setErrorMessage(null)
      opener?.focus()
    }
    const closeAtDesktopBreakpoint = () => {
      if (window.innerWidth <= 767) {
        return
      }
      const desktopTopic = mobileInitialTopic ?? 'region'
      setMobileSheetOpen(false)
      setMobileInitialTopic(null)
      setErrorMessage(null)
      window.requestAnimationFrame(() => {
        triggerRefs.current[desktopTopic]?.focus()
      })
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        close()
        return
      }
      if (event.key !== 'Tab' || mobileSheetRef.current === null) {
        return
      }
      const focusable = focusableElements(mobileSheetRef.current)
      const first = focusable[0]
      const last = focusable.at(-1)
      if (first === undefined || last === undefined) {
        return
      }
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown, true)
    window.addEventListener('resize', closeAtDesktopBreakpoint)
    html.style.overflow = 'hidden'
    body.style.overflow = 'hidden'
    mobileCloseRef.current?.focus()
    if (mobileInitialTopic !== null) {
      const scrollContainer = mobileSheetBodyRef.current
      const target = mobileSheetRef.current?.querySelector<HTMLElement>(
        `[data-mobile-topic="${mobileInitialTopic}"]`,
      )
      if (scrollContainer !== null && target !== null && target !== undefined) {
        const containerTop = scrollContainer.getBoundingClientRect().top
        const targetTop = target.getBoundingClientRect().top
        scrollContainer.scrollTop += targetTop - containerTop - 12
      }
    }
    return () => {
      document.removeEventListener('keydown', handleKeyDown, true)
      window.removeEventListener('resize', closeAtDesktopBreakpoint)
      html.style.overflow = previousHtmlOverflow
      body.style.overflow = previousBodyOverflow
    }
  }, [mobileInitialTopic, mobileSheetOpen])

  useLayoutEffect(() => {
    if (!mobileSheetOpen || !mobileResetFocusPendingRef.current) {
      return
    }
    mobileResetFocusPendingRef.current = false
    mobileResetRef.current?.focus()
  }, [mobileFormRevision, mobileSheetOpen])

  useEffect(() => {
    if (
      !mobileSheetOpen
      || mobileOpenedFiltersSignatureRef.current === filtersSignature
    ) {
      return
    }
    const opener = mobileTriggerRef.current
    setMobileSheetOpen(false)
    setMobileInitialTopic(null)
    setErrorMessage(null)
    opener?.focus()
  }, [filtersSignature, mobileSheetOpen])

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
      const width = Math.min(
        Math.max(280, Math.min(420, requestedWidth)),
        availableWidth,
      )
      const triggerLeft = triggerRect.left - rootRect.left
      const left = Math.max(
        0,
        Math.min(triggerLeft, availableWidth - width),
      )
      const top = Math.max(0, triggerRect.bottom - rootRect.top + 8)
      setPopoverPlacement({ anchorX, left, top, width })
    }

    updateAnchor()
    window.addEventListener('resize', updateAnchor)
    scroller.addEventListener('scroll', updateAnchor, { passive: true })
    return () => {
      window.removeEventListener('resize', updateAnchor)
      scroller.removeEventListener('scroll', updateAnchor)
    }
  }, [openTopic, resolvedRegionSummary])

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

  function openMobileSheet(
    topic: FilterTopic | null,
    trigger: HTMLButtonElement,
  ) {
    mobileTriggerRef.current = trigger
    mobileOpenedFiltersSignatureRef.current = filtersSignature
    setOpenTopic(null)
    setErrorMessage(null)
    setMobileDraftFilters(filters)
    setMobileDraftDirty(false)
    setMobileFormRevision((current) => current + 1)
    setMobileInitialTopic(topic)
    setMobileSheetOpen(true)
  }

  function closeMobileSheet() {
    const opener = mobileTriggerRef.current
    setMobileSheetOpen(false)
    setMobileInitialTopic(null)
    setErrorMessage(null)
    opener?.focus()
  }

  function submitMobileSheet(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = MOBILE_SHEET_TOPICS.reduce<ComplexSearchFilters>(
      (draft, [topic]) => ({ ...draft, ...topicDraftFromForm(topic, data) }),
      {},
    )
    const rangeError = topicRangeError('builtYear', next)
    if (rangeError !== null) {
      setErrorMessage(rangeError)
      return
    }
    onApply(next)
    closeMobileSheet()
  }

  function resetMobileSheet() {
    setErrorMessage(null)
    mobileResetFocusPendingRef.current = true
    setMobileDraftFilters({})
    setMobileDraftDirty(filtersSignature !== '')
    setMobileFormRevision((current) => current + 1)
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
  const resolvedRegionName = resolvedRegionSummary !== null
    && resolvedRegionSummary.regionCode === filters.regionCode
    ? resolvedRegionSummary.label
    : null
  const appliedTopicCount = TOPICS.reduce(
    (count, [topic]) => count + (
      topicSummary(filters, topic, resolvedRegionName) === null ? 0 : 1
    ),
    0,
  )
  const mobileResultAction = !mobileDraftDirty
    && resultCountLabel !== undefined
    && /^\d+곳(?: 이상)?$/.test(resultCountLabel)
    ? `단지 ${resultCountLabel} 보기`
    : '단지 보기'

  return (
    <section ref={rootRef} className={styles.root}>
      <div className={styles.desktopFilters}>
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
                resolvedRegionName,
              )
              const summaryId = `complex-${topic}-filter-summary`
              return (
                <Fragment key={topic}>
                  <button
                    ref={(node) => {
                      if (node === null) {
                        delete triggerRefs.current[topic]
                      } else {
                        triggerRefs.current[topic] = node
                      }
                    }}
                    className={styles.trigger}
                    type="button"
                    title={summary ?? label}
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
                    <span className={styles.triggerText} aria-hidden="true">
                      {summary ?? label}
                    </span>
                    <span
                      className={`${styles.chevron}${
                        expanded ? ` ${styles.chevronExpanded}` : ''
                      }`}
                      aria-hidden="true"
                    />
                  </button>
                  {summary !== null && (
                    <span className={styles.visuallyHidden} id={summaryId}>
                      적용됨: {summary}
                    </span>
                  )}
                </Fragment>
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
              '--popover-top': `${popoverPlacement.top}px`,
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
      </div>

      <div
        className={styles.mobileToolbar}
        role="toolbar"
        aria-label="모바일 단지 검색 필터"
      >
        {MOBILE_PRIMARY_TOPICS.map(([topic, label]) => {
          const summary = topicSummary(filters, topic, resolvedRegionName)
          return (
            <button
              key={topic}
              className={styles.mobileTrigger}
              type="button"
              title={summary ?? label}
              aria-controls="mobile-complex-filter-sheet"
              aria-expanded={mobileSheetOpen}
              aria-label={summary === null
                ? `${label} 조건으로 전체 단지 필터 열기`
                : `${label} ${summary}, 전체 단지 필터 열기`}
              data-active={summary === null ? 'false' : 'true'}
              onClick={(event) => openMobileSheet(topic, event.currentTarget)}
            >
              <span>{summary ?? label}</span>
            </button>
          )
        })}
        <button
          className={styles.mobileAllTrigger}
          type="button"
          aria-controls="mobile-complex-filter-sheet"
          aria-expanded={mobileSheetOpen}
          aria-label={appliedTopicCount > 0
            ? `전체 단지 필터 열기, ${appliedTopicCount}개 적용`
            : '전체 단지 필터 열기'}
          data-active={appliedTopicCount > 0 ? 'true' : 'false'}
          onClick={(event) => openMobileSheet(null, event.currentTarget)}
        >
          필터{appliedTopicCount > 0 ? ` ${appliedTopicCount}` : ''}
        </button>
      </div>

      {mobileSheetOpen && (
        <div
          className={styles.mobileBackdrop}
          onPointerDown={(event) => {
            if (event.target === event.currentTarget) {
              closeMobileSheet()
            }
          }}
        >
          <section
            ref={mobileSheetRef}
            className={styles.mobileSheet}
            id="mobile-complex-filter-sheet"
            role="dialog"
            aria-modal="true"
            aria-labelledby="mobile-complex-filter-heading"
          >
            <form
              key={`mobile-${mobileFormRevision}-${searchFiltersSignature(mobileDraftFilters)}`}
              className={styles.mobileForm}
              aria-label="단지 필터 조건"
              onSubmit={submitMobileSheet}
            >
              <header className={styles.mobileSheetHeader}>
                <h2 id="mobile-complex-filter-heading">단지 필터</h2>
                <div className={styles.mobileHeaderActions}>
                  <button
                    ref={mobileResetRef}
                    className={styles.mobileReset}
                    type="button"
                    aria-label="전체 필터 초기화"
                    onClick={resetMobileSheet}
                  >초기화</button>
                  <button
                    ref={mobileCloseRef}
                    className={styles.mobileClose}
                    type="button"
                    aria-label="단지 필터 닫기"
                    onClick={closeMobileSheet}
                  >×</button>
                </div>
              </header>

              <div
                ref={mobileSheetBodyRef}
                className={styles.mobileSheetBody}
                onClickCapture={(event) => {
                  if (
                    event.target instanceof Element
                    && event.target.closest('button') !== null
                  ) {
                    setMobileDraftDirty(true)
                  }
                }}
                onChange={() => setMobileDraftDirty(true)}
              >
                {MOBILE_SHEET_TOPICS.map(([topic, label]) => (
                  <section
                    key={topic}
                    className={styles.mobileTopic}
                    data-mobile-topic={topic}
                  >
                    {(topic === 'region' || topic === 'price') && (
                      <h3>{label}</h3>
                    )}
                    <TopicFields
                      filters={mobileDraftFilters}
                      regionRepository={regionRepository}
                      topic={topic}
                    />
                  </section>
                ))}
                {errorMessage !== null && (
                  <p className={styles.mobileError} role="alert">
                    {errorMessage}
                  </p>
                )}
              </div>

              <div className={styles.mobileFooter}>
                <button
                  className={styles.mobileApply}
                  type="submit"
                  aria-label={mobileResultAction}
                >{mobileResultAction}</button>
              </div>
            </form>
          </section>
        </div>
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
      const label = name === null
        ? filters.regionCode
        : filters.regionCode.length === 5
          ? resolvedRegionName ?? `${name} ${filters.regionCode}`
          : name
      return compactRegionLabel(label)
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
        deposit,
        rent === null ? null : `월 ${rent}`,
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
  const labels = values.map((selected) =>
    options.find(([value]) => value === selected)?.[1] ?? selected)
  return labels.length > 1 ? `${labels[0]} 외 ${labels.length - 1}` : labels[0]
}

const COMPACT_PROVINCE_NAMES = new Map<string, string>([
  ['서울특별시', '서울'],
  ['전남광주통합특별시', '광주'],
  ['부산광역시', '부산'],
  ['대구광역시', '대구'],
  ['인천광역시', '인천'],
  ['대전광역시', '대전'],
  ['울산광역시', '울산'],
  ['세종특별자치시', '세종'],
  ['경기도', '경기'],
  ['충청북도', '충북'],
  ['충청남도', '충남'],
  ['경상북도', '경북'],
  ['경상남도', '경남'],
  ['제주특별자치도', '제주'],
  ['강원특별자치도', '강원'],
  ['전북특별자치도', '전북'],
])

function compactRegionLabel(label: string) {
  const province = [...COMPACT_PROVINCE_NAMES.entries()].find(
    ([fullName]) => label === fullName || label.startsWith(`${fullName} `),
  )
  if (province === undefined) {
    return label
  }
  const [fullName, compactName] = province
  return `${compactName}${label.slice(fullName.length)}`
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

function focusableElements(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>(
    'button:not([disabled]), select:not([disabled]), input:not([type="hidden"]):not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute('hidden'))
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
