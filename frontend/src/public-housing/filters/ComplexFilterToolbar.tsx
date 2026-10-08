import { createPortal } from 'react-dom'
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
import { AllFilterFields, ComplexFilterFields } from './ComplexFilterFields.tsx'
import {
  topicDraftFromForm, topicsDraftFromForm, replaceTopic, topicRangeError,
} from './searchFilterForm.ts'
import {
  type DesktopFilterTopic,
  DESKTOP_PRIMARY_TOPICS, DESKTOP_TOPICS,
  MOBILE_PRIMARY_TOPICS, MOBILE_SHEET_TOPICS, POPOVER_WIDTHS, TOPICS,
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
}: ComplexFilterToolbarProps) {
  const filtersSignature = searchFiltersSignature(filters)
  const [openTopic, setOpenTopic] = useState<DesktopFilterTopic | null>(null)
  const [allDraftFilters, setAllDraftFilters] = useState(filters)
  const [allFormRevision, setAllFormRevision] = useState(0)
  const [mobileSheetOpen, setMobileSheetOpen] = useState(false)
  const [mobileDraftFilters, setMobileDraftFilters] = useState(filters)
  const [mobileFormRevision, setMobileFormRevision] = useState(0)
  const [mobileInitialTopic, setMobileInitialTopic] =
    useState<DesktopFilterTopic | null>(null)
  const [rovingTopic, setRovingTopic] =
    useState<DesktopFilterTopic>('all')
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
  const desktopResetRef = useRef<HTMLButtonElement>(null)
  const desktopResetFocusPendingRef = useRef(false)
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
    if (quickAppliedSignatureRef.current !== filtersSignature && (openTopic === 'all' || !explicitlyApplied(openTopic))) {
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

  useLayoutEffect(() => {
    if (!desktopResetFocusPendingRef.current) return
    desktopResetFocusPendingRef.current = false
    desktopResetRef.current?.focus()
  }, [allFormRevision])

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
    topic: DesktopFilterTopic | null,
    trigger: HTMLButtonElement,
  ) {
    mobileTriggerRef.current = trigger
    mobileOpenedFiltersSignatureRef.current = filtersSignature
    setOpenTopic(null)
    setErrorMessage(null)
    setMobileDraftFilters(filters)
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
    if (mobileInitialTopic === null) return
    const next = mobileInitialTopic === 'all'
      ? topicsDraftFromForm(TOPICS, data)
      : replaceTopic(filters, mobileInitialTopic, topicDraftFromForm(mobileInitialTopic, data))
    const rangeError = topicRangeError(mobileInitialTopic === 'all' ? 'builtYear' : mobileInitialTopic, next)
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
    setMobileFormRevision((current) => current + 1)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (openTopic === null) {
      return
    }
    const data = new FormData(event.currentTarget)
    const draft = openTopic === 'all' ? topicsDraftFromForm(TOPICS, data) : topicDraftFromForm(openTopic, data)
    const rangeError = topicRangeError(openTopic === 'all' ? 'builtYear' : openTopic, draft)
    if (rangeError !== null) {
      setErrorMessage(rangeError)
      return
    }
    applyAndClose(openTopic, openTopic === 'all' ? draft : replaceTopic(filters, openTopic, draft))
  }

  function applyQuickFilter(rangeValues: Readonly<Record<string, number | null>> = {}) {
    if (openTopic === null || openTopic === 'all' || explicitlyApplied(openTopic) || desktopFormRef.current === null) return
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

  function resetAllDraft() {
    setErrorMessage(null)
    setAllDraftFilters({})
    desktopResetFocusPendingRef.current = true
    setAllFormRevision((current) => current + 1)
  }

  const openLabel = desktopTopicLabel(openTopic)
  const headingId = openTopic === null
    ? undefined
    : `complex-${openTopic}-filter-heading`
  const resolvedRegionName = resolvedRegionSummary !== null
    && resolvedRegionSummary.regionCode === filters.regionCode
    ? resolvedRegionSummary.label
    : null
  const appliedFilterCount = TOPICS.filter(([topic]) => topicSummary(filters, topic, resolvedRegionName) !== null).length
  const filterCountDescriptionId = appliedFilterCount > 0 ? 'complex-applied-filter-count' : undefined
  const mobileResultAction = `${desktopTopicLabel(mobileInitialTopic) ?? '필터'} 적용`

  return (
    <section ref={rootRef} className={styles.root}>
      {appliedFilterCount > 0 && (
        <span id={filterCountDescriptionId} className={styles.visuallyHidden}>
          적용 중인 필터 {appliedFilterCount}개
        </span>
      )}
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
                const summary = topic === 'all' ? null : topicSummary(
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
                        topic === 'all' ? filterCountDescriptionId : summary === null ? undefined : summaryId
                      }
                      aria-expanded={expanded}
                      aria-label={`${label} 필터 ${expanded ? '닫기' : '열기'}`}
                      data-active={topic === 'all' ? appliedFilterCount > 0 : summary !== null}
                      onFocus={() => setRovingTopic(topic)}
                      onClick={() => {
                        setErrorMessage(null)
                        if (topic === 'all') {
                          setAllDraftFilters(filters)
                          setAllFormRevision((current) => current + 1)
                        }
                        setOpenTopic(
                          (current) => current === topic ? null : topic,
                        )
                      }}
                    >
                      {topic === 'all' && <AllFiltersIcon />}
                      <span className={styles.triggerText} aria-hidden="true">
                        {summary ?? label}
                      </span>
                      {topic === 'all' && appliedFilterCount > 0 && (
                        <span className={styles.filterCount} aria-hidden="true">{appliedFilterCount}</span>
                      )}
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
              key={openTopic === 'all' ? `all-${allFormRevision}` : explicitlyApplied(openTopic) ? `${openTopic}-${filtersSignature}` : openTopic}
              ref={desktopFormRef}
              className={styles.form}
              onSubmit={submit}
              onChange={(event) => {
                if (event.target instanceof HTMLInputElement && event.target.type === 'range') return
                applyQuickFilter()
              }}
            >
              <header className={styles.popoverHeader} data-all={openTopic === 'all'}>
                {openTopic === 'all' && <button
                  ref={desktopResetRef}
                  className={styles.reset}
                  type="button"
                  aria-label={`${openLabel} 필터 초기화`}
                  onClick={resetAllDraft}
                >
                  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                    <path d="M16.4 8a6.5 6.5 0 1 0-.3 4.8M16.5 3.5V8H12" />
                  </svg>
                  초기화
                </button>}
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
                {openTopic === 'all'
                  ? <AllFilterFields filters={allDraftFilters} regionRepository={regionRepository} />
                  : <ComplexFilterFields filters={filters} regionRepository={regionRepository}
                    topic={openTopic} onRangeChange={applyQuickFilter} />}
              </div>
              {errorMessage !== null && (
                <p className={styles.error} role="alert">{errorMessage}</p>
              )}
              {explicitlyApplied(openTopic) && <div className={styles.actions}>
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
        <div className={styles.mobileScroller}>
        {MOBILE_PRIMARY_TOPICS.map(([topic, label]) => {
          const summary = topic === 'all' ? null : topicSummary(filters, topic, resolvedRegionName)
          return (
            <button
              key={topic}
              className={styles.mobileTrigger}
              type="button"
              title={summary ?? label}
              aria-controls="mobile-complex-filter-sheet"
              aria-expanded={mobileSheetOpen && mobileInitialTopic === topic}
              aria-label={`모바일 ${label} 필터 열기`}
              aria-describedby={topic === 'all' ? filterCountDescriptionId : undefined}
              data-active={topic === 'all' ? appliedFilterCount > 0 : summary !== null}
              onClick={(event) => openMobileSheet(topic, event.currentTarget)}
            >
              {topic === 'all' && <AllFiltersIcon />}
              <span>{summary ?? label}</span>
              {topic === 'all' && appliedFilterCount > 0 && (
                <span className={styles.filterCount} aria-hidden="true">{appliedFilterCount}</span>
              )}
            </button>
          )
        })}
        </div>
      </div>

      {mobileSheetOpen && createPortal(
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
              <header className={styles.mobileSheetHeader} data-all={mobileInitialTopic === 'all'}>
                {mobileInitialTopic === 'all' && <button
                  ref={mobileResetRef}
                  className={styles.mobileReset}
                  type="button"
                  aria-label={`${desktopTopicLabel(mobileInitialTopic)} 필터 초기화`}
                  onClick={resetMobileSheet}
                >
                  <span className={styles.mobileResetContent}>
                    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                      <path d="M16.4 8a6.5 6.5 0 1 0-.3 4.8M16.5 3.5V8H12" />
                    </svg>
                    초기화
                  </span>
                </button>}
                <h2 id="mobile-complex-filter-heading">{desktopTopicLabel(mobileInitialTopic)} 필터</h2>
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
              >
                {MOBILE_SHEET_TOPICS.filter(([topic]) => mobileInitialTopic === 'all' || topic === mobileInitialTopic).map(([topic, label]) => (
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
        </div>,
        document.body,
      )}
    </section>
  )
}

function AllFiltersIcon() {
  return (
    <svg className={styles.detailIcon} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M3 7h4m4 0h10M3 17h10m4 0h4" />
      <circle cx="9" cy="7" r="2" />
      <circle cx="15" cy="17" r="2" />
    </svg>
  )
}

function explicitlyApplied(topic: DesktopFilterTopic | null) {
  return topic === 'all' || topic === 'region' || topic === 'agency' || topic === 'recruitmentType'
}

function focusableElements(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>(
    'button:not([disabled]), select:not([disabled]), input:not([type="hidden"]):not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute('hidden'))
}
