import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import {
  type CSSProperties,
  Fragment,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import {
  type PublicHousingRegionRepository,
  publicHousingRegionRepository,
} from '../api/publicHousingRegionRepository.ts'
import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'
import { provinceNameForRegionCode } from '../model/publicHousingRegion.ts'
import { ComplexFilterFields, DetailFilterFields } from './ComplexFilterFields.tsx'
import {
  topicDraftFromForm, topicsDraftFromForm, replaceTopic, replaceTopics, topicRangeError,
} from './complexFilterForm.ts'
import {
  type DesktopFilterTopic, type FilterTopic,
  TOPICS, DESKTOP_PRIMARY_TOPICS, DESKTOP_TOPICS, DETAIL_FILTER_TOPICS,
  MOBILE_PRIMARY_TOPICS, MOBILE_SHEET_TOPICS, POPOVER_WIDTHS,
  desktopTopicFor, desktopTopicLabel,
} from './complexFilterTopics.ts'
import { topicSummary } from './complexFilterPresentation.ts'
import { searchFiltersSignature } from './searchFilterLocation.ts'
import styles from './ComplexFilterToolbar.module.css'

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
  const [openTopic, setOpenTopic] = useState<DesktopFilterTopic | null>(null)
  const [mobileSheetOpen, setMobileSheetOpen] = useState(false)
  const [mobileDraftFilters, setMobileDraftFilters] = useState(filters)
  const [mobileDraftDirty, setMobileDraftDirty] = useState(false)
  const [mobileFormRevision, setMobileFormRevision] = useState(0)
  const [mobileInitialTopic, setMobileInitialTopic] =
    useState<FilterTopic | null>(null)
  const [rovingTopic, setRovingTopic] =
    useState<DesktopFilterTopic>('rentalType')
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
  const desktopFormRef = useRef<HTMLFormElement>(null)
  const previousFiltersSignatureRef = useRef(filtersSignature)
  const quickAppliedSignatureRef = useRef<string | null>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const mobileSheetRef = useRef<HTMLElement>(null)
  const mobileSheetBodyRef = useRef<HTMLDivElement>(null)
  const mobileCloseRef = useRef<HTMLButtonElement>(null)
  const mobileResetRef = useRef<HTMLButtonElement>(null)
  const mobileResetFocusPendingRef = useRef(false)
  const mobileTriggerRef = useRef<HTMLButtonElement | null>(null)
  const mobileOpenedFiltersSignatureRef = useRef(filtersSignature)
  const triggerRefs = useRef<
    Partial<Record<DesktopFilterTopic, HTMLButtonElement>>
  >({})

  useEffect(() => {
    if (previousFiltersSignatureRef.current === filtersSignature) return
    previousFiltersSignatureRef.current = filtersSignature
    if (quickAppliedSignatureRef.current !== filtersSignature && openTopic !== 'detail') {
      setOpenTopic(null)
      setErrorMessage(null)
    }
    quickAppliedSignatureRef.current = null
  }, [filtersSignature, openTopic])

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
      const desktopTopic = desktopTopicFor(mobileInitialTopic)
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
      const requestedWidth = openTopic === 'detail'
        ? 420
        : POPOVER_WIDTHS[openTopic]
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
    const currentIndex = DESKTOP_TOPICS.findIndex(
      ([topic]) => triggerRefs.current[topic] === event.target,
    )
    if (currentIndex < 0) {
      return
    }
    event.preventDefault()
    const lastIndex = DESKTOP_TOPICS.length - 1
    const nextIndex = event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? lastIndex
        : event.key === 'ArrowRight'
          ? (currentIndex + 1) % DESKTOP_TOPICS.length
          : (currentIndex - 1 + DESKTOP_TOPICS.length) % DESKTOP_TOPICS.length
    const nextTopic = DESKTOP_TOPICS[nextIndex][0]
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
    const next = topicsDraftFromForm(MOBILE_SHEET_TOPICS, data)
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
    const data = new FormData(event.currentTarget)
    if (openTopic === 'detail') {
      const draft = topicsDraftFromForm(DETAIL_FILTER_TOPICS, data)
      applyAndClose(
        openTopic,
        replaceTopics(filters, DETAIL_FILTER_TOPICS, draft),
      )
      return
    }
    const draft = topicDraftFromForm(openTopic, data)
    const rangeError = topicRangeError(openTopic, draft)
    if (rangeError !== null) {
      setErrorMessage(rangeError)
      return
    }
    applyAndClose(openTopic, replaceTopic(filters, openTopic, draft))
  }

  function applyQuickFilter(rangeValues: Readonly<Record<string, number | null>> = {}) {
    if (openTopic === null || openTopic === 'detail' || desktopFormRef.current === null) return
    const data = new FormData(desktopFormRef.current)
    // Range inputs report both endpoints before their hidden inputs re-render.
    Object.entries(rangeValues).forEach(([key, value]) => data.set(key, value === null ? '' : String(value)))
    const draft = topicDraftFromForm(openTopic, data)
    const rangeError = topicRangeError(openTopic, draft)
    setErrorMessage(rangeError)
    if (rangeError !== null) return
    const next = replaceTopic(filters, openTopic, draft)
    const nextSignature = searchFiltersSignature(next)
    if (nextSignature === filtersSignature) return
    quickAppliedSignatureRef.current = nextSignature
    onApply(next)
  }

  function applyAndClose(
    topic: DesktopFilterTopic,
    next: ComplexSearchFilters,
  ) {
    setErrorMessage(null)
    onApply(next)
    setOpenTopic(null)
    triggerRefs.current[topic]?.focus()
  }

  function resetOpenFilter() {
    if (openTopic === null) {
      return
    }
    const next = openTopic === 'detail'
      ? replaceTopics(filters, DETAIL_FILTER_TOPICS, {})
      : replaceTopic(filters, openTopic, {})
    applyAndClose(openTopic, next)
  }

  const openLabel = desktopTopicLabel(openTopic)
  const headingId = openTopic === null
    ? undefined
    : `complex-${openTopic}-filter-heading`
  const resolvedRegionName = resolvedRegionSummary !== null
    && resolvedRegionSummary.regionCode === filters.regionCode
    ? resolvedRegionSummary.label
    : null
  const detailAppliedCount = DETAIL_FILTER_TOPICS.reduce(
    (count, [topic]) => count + (
      topicSummary(filters, topic, resolvedRegionName) === null ? 0 : 1
    ),
    0,
  )
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
        <div
          className={styles.toolbar}
          role="toolbar"
          aria-label="단지 검색 필터"
          onKeyDown={moveToolbarFocus}
        >
          <div ref={scrollerRef} className={styles.scroller}>
            <div className={styles.primaryFilters}>
              {DESKTOP_PRIMARY_TOPICS.map(([topic, label]) => {
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
                      aria-describedby={
                        summary === null ? undefined : summaryId
                      }
                      aria-expanded={expanded}
                      aria-label={`${label} 필터 ${expanded ? '닫기' : '열기'}`}
                      data-active={summary === null ? 'false' : 'true'}
                      onFocus={() => setRovingTopic(topic)}
                      onClick={() => {
                        setErrorMessage(null)
                        setOpenTopic(
                          (current) => current === topic ? null : topic,
                        )
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

          <button
            ref={(node) => {
              if (node === null) {
                delete triggerRefs.current.detail
              } else {
                triggerRefs.current.detail = node
              }
            }}
            className={`${styles.trigger} ${styles.detailTrigger}`}
            type="button"
            title="상세 필터"
            tabIndex={rovingTopic === 'detail' ? 0 : -1}
            aria-controls="complex-detail-filter-popover"
            aria-describedby={detailAppliedCount === 0
              ? undefined
              : 'complex-detail-filter-summary'}
            aria-expanded={openTopic === 'detail'}
            aria-label={`상세 필터 ${
              openTopic === 'detail' ? '닫기' : '열기'
            }`}
            data-active={detailAppliedCount === 0 ? 'false' : 'true'}
            onFocus={() => setRovingTopic('detail')}
            onClick={() => {
              setErrorMessage(null)
              setOpenTopic((current) => current === 'detail' ? null : 'detail')
            }}
          >
            <DetailFilterIcon />
            {detailAppliedCount > 0 && (
              <span className={styles.detailBadge} aria-hidden="true">
                {detailAppliedCount}
              </span>
            )}
          </button>
          {detailAppliedCount > 0 && (
            <span
              className={styles.visuallyHidden}
              id="complex-detail-filter-summary"
            >
              상세 필터 {detailAppliedCount}개 적용
            </span>
          )}
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
              key={openTopic === 'detail' ? `${openTopic}-${filtersSignature}` : openTopic}
              ref={desktopFormRef}
              className={styles.form}
              onSubmit={submit}
              onChange={(event) => {
                if (event.target instanceof HTMLInputElement && event.target.type === 'range') return
                applyQuickFilter()
              }}
            >
              <header className={styles.popoverHeader}>
                <button
                  className={styles.reset}
                  type="button"
                  aria-label={`${openLabel} 필터 초기화`}
                  onClick={resetOpenFilter}
                >
                  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                    <path d="M16.4 8a6.5 6.5 0 1 0-.3 4.8M16.5 3.5V8H12" />
                  </svg>
                  초기화
                </button>
                <h2 className={styles.popoverHeading} id={headingId}>
                  {openLabel} 필터
                </h2>
                <IconButton
                  className={styles.close}
                  type="button"
                  label={`${openLabel} 필터 패널 닫기`}
                  onClick={() => {
                    setOpenTopic(null)
                    setErrorMessage(null)
                    triggerRefs.current[openTopic]?.focus()
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
                    <path d="m5 5 14 14M19 5 5 19" />
                  </svg>
                </IconButton>
              </header>
              <div className={styles.fields}>
                {openTopic === 'detail' ? (
                  <DetailFilterFields
                    filters={filters}
                    regionRepository={regionRepository}
                  />
                ) : (
                  <ComplexFilterFields
                    filters={filters}
                    regionRepository={regionRepository}
                    topic={openTopic}
                    onRangeChange={applyQuickFilter}
                  />
                )}
              </div>
              {errorMessage !== null && (
                <p className={styles.error} role="alert">{errorMessage}</p>
              )}
              {openTopic === 'detail' && <div className={styles.actions}>
                <Button
                  className={styles.apply}
                  type="submit"
                  aria-label={`${openLabel} 필터 적용`}
                >적용</Button>
              </div>}
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
                <button
                  ref={mobileResetRef}
                  className={styles.mobileReset}
                  type="button"
                  aria-label="전체 필터 초기화"
                  onClick={resetMobileSheet}
                >
                  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                    <path d="M16.4 8a6.5 6.5 0 1 0-.3 4.8M16.5 3.5V8H12" />
                  </svg>
                  초기화
                </button>
                <h2 id="mobile-complex-filter-heading">단지 필터</h2>
                <IconButton size="lg"
                  ref={mobileCloseRef}
                  className={styles.mobileClose}
                  type="button"
                  label="단지 필터 닫기"
                  onClick={closeMobileSheet}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
                    <path d="m5 5 14 14M19 5 5 19" />
                  </svg>
                </IconButton>
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
                    <ComplexFilterFields
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
                <Button size="lg"
                  className={styles.mobileApply}
                  type="submit"
                  aria-label={mobileResultAction}
                >{mobileResultAction}</Button>
              </div>
            </form>
          </section>
        </div>
      )}
    </section>
  )
}

function DetailFilterIcon() {
  return (
    <svg
      className={styles.detailIcon}
      viewBox="0 0 20 20"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M3 5.5h5M12 5.5h5" />
      <circle cx="10" cy="5.5" r="2" />
      <path d="M3 14.5h2M9 14.5h8" />
      <circle cx="7" cy="14.5" r="2" />
    </svg>
  )
}

function focusableElements(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>(
    'button:not([disabled]), select:not([disabled]), input:not([type="hidden"]):not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute('hidden'))
}
