import { toHttpUrl } from '../presentation/httpUrl.ts'
import {
  type KeyboardEvent,
  type RefObject,
  type ReactNode,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  DetailCloseButton,
  DetailFact,
  DetailFacts,
  DetailSection,
  DetailTable,
} from './DetailPrimitives.tsx'
import { AttachmentDialog, AttachmentList } from './AnnouncementAttachments.tsx'
import { hasAttachmentUrl } from '../api/announcementAttachments.ts'
import { AnnouncementStatusBadge } from './AnnouncementStatusBadge.tsx'
import { ApplicationScheduleGroups } from './ApplicationScheduleGroups'
import type {
  AnnouncementApplicationSchedule,
  AnnouncementHousingType,
  AnnouncementSupplyComplex,
  AnnouncementSupplyTarget,
} from '../model/publicHousing.ts'
import {
  groupAnnouncementSupplyRows,
  type HousingAnnouncementSupplyComplexGroup,
} from '../presentation/announcementDetailPresentation.ts'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { formatPhoneNumber } from '../presentation/phoneNumber'
import { groupAnnouncementSchedules } from '../presentation/announcementSchedulePresentation'
import { ScheduleGroupCard } from './ScheduleGroupCard'
import { SchedulePeriod } from './SchedulePeriod'
import scheduleStyles from './ScheduleGroups.module.css'
import { formatHousingMoneyWon } from '../presentation/housingMoney'
import styles from './HousingAnnouncementDetailPanel.module.css'
import { NotificationInterestButton } from '../interest/NotificationInterest'

export interface HousingAnnouncementDetailReceptionPlace {
  readonly name: string | null
  readonly methodLabel: string
  readonly address: string | null
  readonly phoneNumber: string | null
  readonly url: string | null
}

export interface HousingAnnouncementDetailSchedule {
  readonly scheduleId: string
  readonly complexName?: string | null
  readonly type: string | null
  readonly typeLabel: string
  readonly name: string | null
  readonly startAt: string | null
  readonly endAt: string | null
}

export interface HousingAnnouncementDetailAttachment {
  readonly attachmentId: string
  readonly fileName: string | null
  readonly fileTypeLabel: string
  readonly fileUrl: string | null
}

export interface HousingAnnouncementDetailSupplyRow {
  readonly supplyRowId: string
  readonly sourceComplexName: string | null
  readonly sourceHousingTypeName: string | null
  readonly complex: AnnouncementSupplyComplex | null
  readonly housingType: AnnouncementHousingType | null
  readonly occupancyExpectedYearMonth: string | null
  readonly supplyTypeLabel: string
  readonly totalSupplyHouseholdCount: number | null
  readonly targets: readonly AnnouncementSupplyTarget[]
}

export interface HousingAnnouncementDetailData {
  readonly announcementId: string
  readonly publicationTypeLabel: string
  readonly correctionOrCancellationReason: string | null
  readonly applicationStatus: string | null
  readonly applicationStatusLabel: string
  readonly rentalTypeLabel: string
  readonly recruitmentTypeLabel: string
  readonly title: string | null
  readonly regionNames: readonly string[]
  readonly agencyCode: string | null
  readonly agencyName: string | null
  readonly publishedAt: string | null
  readonly applicationStartAt: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
  readonly winnerAnnouncementAt: string | null
  readonly viewCount: number
  readonly targets: readonly string[]
  readonly supplyComplexCount: number
  readonly supplyHouseholdCount: number | null
  readonly documentLinkUrl: string | null
  readonly receptionPlaces: readonly HousingAnnouncementDetailReceptionPlace[]
  readonly schedules: readonly HousingAnnouncementDetailSchedule[]
  readonly applicationSchedules?: readonly AnnouncementApplicationSchedule[]
  readonly attachments: readonly HousingAnnouncementDetailAttachment[]
  readonly supplyRows: readonly HousingAnnouncementDetailSupplyRow[]
}

export interface HousingAnnouncementDetailPanelProps {
  readonly detail: HousingAnnouncementDetailData
  readonly onClose: () => void
  readonly backButton?: ReactNode
  readonly onOpenComplex?: (complexId: string) => void
}

interface ComplexSelection {
  readonly announcementId: string
  readonly groupKey: string | null
}

interface FloorPlanSelection {
  readonly complexName: string
  readonly housingTypeName: string
  readonly twoDimensionalUrl: string | null
  readonly threeDimensionalUrl: string | null
}

const DETAIL_SECTIONS = [
  { key: 'summary', label: '요약' },
  { key: 'schedule', label: '일정' },
  { key: 'housing', label: '주택형' },
] as const

type DetailSectionKey = typeof DETAIL_SECTIONS[number]['key']

export function HousingAnnouncementDetailPanel({
  detail,
  onClose,
  onOpenComplex,
  backButton,
}: HousingAnnouncementDetailPanelProps) {
  const sectionId = useId()
  const { scrollRef, sectionRefs, activeSection, trackSection, scrollToSection } =
    useAnnouncementSections(detail.announcementId)
  const groups = useMemo(
    () => groupAnnouncementSupplyRows(detail.supplyRows),
    [detail.supplyRows],
  )
  const firstGroupKey = groups[0]?.key ?? null
  const [selection, setSelection] = useState<ComplexSelection>({
    announcementId: detail.announcementId,
    groupKey: firstGroupKey,
  })
  const [floorPlan, setFloorPlan] = useState<FloorPlanSelection | null>(null)
  const [attachmentAnnouncementId, setAttachmentAnnouncementId] = useState<string | null>(null)
  const selectedGroupKey = selection.announcementId === detail.announcementId
    ? selection.groupKey
    : firstGroupKey
  const selectedGroup = groups.find((group) => group.key === selectedGroupKey)
    ?? groups[0]
  const title = detail.title ?? MISSING_DATA_LABEL
  const titleId = `announcement-detail-title-${detail.announcementId}`
  const headingRef = useRef<HTMLHeadingElement>(null)
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true })
  }, [detail.announcementId])

  function selectGroup(group: HousingAnnouncementSupplyComplexGroup) {
    setSelection({
      announcementId: detail.announcementId,
      groupKey: group.key,
    })
  }

  function handlePanelKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape') {
      return
    }
    event.stopPropagation()
    onClose()
  }

  function handleGroupKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) {
    const nextIndex = tabIndexForKey(event.key, index, groups.length)
    if (nextIndex === null) {
      return
    }
    const nextGroup = groups[nextIndex]
    if (!nextGroup) {
      return
    }
    event.preventDefault()
    selectGroup(nextGroup)
    tabRefs.current.get(nextGroup.key)?.focus()
  }

  return (
    <aside
      className={styles.panel}
      aria-label={`${title} 상세 정보`}
      onKeyDown={handlePanelKeyDown}
    >
      <StickyHeader
        backButton={backButton}
        detail={detail}
        title={title}
        titleId={titleId}
        headingRef={headingRef}
        onClose={onClose}
      />

      <nav className={styles.sectionNav} aria-label="공고 상세 섹션">
        {DETAIL_SECTIONS.map(({ key, label }) => (
          <a key={key} href={`#${sectionId}-${key}`}
            aria-current={activeSection === key ? 'location' : undefined}
            onClick={(event) => {
              event.preventDefault()
              scrollToSection(key)
            }}>
            {label}
          </a>
        ))}
      </nav>

      <div
        ref={scrollRef}
        onScroll={trackSection}
        className={styles.scroll}
        role="region"
        aria-label={`${title} 상세 내용`}
        tabIndex={0}
      >
        <div id={`${sectionId}-summary`} className={styles.sectionGroup}
          role="region" aria-label="요약 영역" tabIndex={-1}
          ref={(node) => { sectionRefs.current.summary = node }}>
          <NoticeIntro detail={detail} groups={groups} />
          <NotificationInterestButton
            target={{ type: 'ANNOUNCEMENT', id: detail.announcementId, name: title }}
            source="ANNOUNCEMENT_DETAIL"
          />
          <CoreInformation detail={detail} />
          <ReasonNotice detail={detail} />
        </div>
        <div id={`${sectionId}-schedule`} className={styles.sectionGroup}
          role="region" aria-label="일정 영역" tabIndex={-1}
          ref={(node) => { sectionRefs.current.schedule = node }}>
          <ScheduleSection detail={detail} groups={groups} />
          <ReceptionPlaces places={detail.receptionPlaces} />
        </div>
        <div id={`${sectionId}-housing`} className={styles.sectionGroup}
          role="region" aria-label="주택형 영역" tabIndex={-1}
          ref={(node) => { sectionRefs.current.housing = node }}>
          <ComplexComparison
            groups={groups}
            rentalTypeLabel={detail.rentalTypeLabel}
            supplyComplexCount={detail.supplyComplexCount}
            supplyHouseholdCount={detail.supplyHouseholdCount}
            onOpenComplex={onOpenComplex}
          />
          {selectedGroup && (
            <HousingTypeComparison
              groups={groups}
              selectedGroup={selectedGroup}
              tabRefs={tabRefs.current}
              onSelectGroup={selectGroup}
              onGroupKeyDown={handleGroupKeyDown}
              onOpenFloorPlan={setFloorPlan}
            />
          )}
          <AttachmentList key={detail.announcementId} announcementId={detail.announcementId} attachments={detail.attachments} />
        </div>
      </div>

      <DocumentActions detail={detail} onOpenAttachments={() => setAttachmentAnnouncementId(detail.announcementId)} />
      {attachmentAnnouncementId === detail.announcementId && (
        <AttachmentDialog key={detail.announcementId} announcementId={detail.announcementId} attachments={detail.attachments}
          onClose={() => setAttachmentAnnouncementId(null)} />
      )}
      {floorPlan && (
        <FloorPlanDialog selection={floorPlan} onClose={() => setFloorPlan(null)} />
      )}
    </aside>
  )
}

function useAnnouncementSections(announcementId: string) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const sectionRefs = useRef<Record<DetailSectionKey, HTMLDivElement | null>>({
    summary: null, schedule: null, housing: null,
  })
  const [selection, setSelection] = useState<{ announcementId: string; key: DetailSectionKey }>({
    announcementId, key: 'summary',
  })
  const activeSection = selection.announcementId === announcementId ? selection.key : 'summary'

  const trackSection = useCallback(() => {
    const scroll = scrollRef.current
    if (!scroll) return
    let key: DetailSectionKey = 'summary'
    if (scroll.scrollTop > 0) {
      const top = scroll.getBoundingClientRect().top
      for (const section of DETAIL_SECTIONS) {
        const node = sectionRefs.current[section.key]
        if (node && node.getBoundingClientRect().top <= top + 24) key = section.key
      }
      // A short final section may never reach the top of the scroll container.
      if (scroll.scrollTop + scroll.clientHeight >= scroll.scrollHeight - 1) key = 'housing'
    }
    setSelection((previous) => previous.announcementId === announcementId && previous.key === key
      ? previous : { announcementId, key })
  }, [announcementId])

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = 0
  }, [announcementId])

  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(trackSection)
    if (scrollRef.current) observer.observe(scrollRef.current)
    for (const section of Object.values(sectionRefs.current)) {
      if (section) observer.observe(section)
    }
    return () => observer.disconnect()
  }, [trackSection])

  function scrollToSection(key: DetailSectionKey) {
    const scroll = scrollRef.current
    const section = sectionRefs.current[key]
    if (!scroll || !section) return
    const top = section.getBoundingClientRect().top - scroll.getBoundingClientRect().top + scroll.scrollTop
    section.focus({ preventScroll: true })
    scroll.scrollTo({ top: Math.max(0, top),
      behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
  }

  return { scrollRef, sectionRefs, activeSection, trackSection, scrollToSection }
}

function StickyHeader({
  backButton,
  detail,
  title,
  titleId,
  headingRef,
  onClose,
}: {
  detail: HousingAnnouncementDetailData
  title: string
  titleId: string
  headingRef: RefObject<HTMLHeadingElement | null>
  backButton?: ReactNode
  onClose: () => void
}) {
  const hasCountdown = (detail.applicationStatus === 'APPLYING'
    || detail.applicationStatus === 'BEFORE_APPLICATION')
    && detail.dDay !== null && Number.isFinite(detail.dDay) && detail.dDay >= 0
  const statusLabel = detail.applicationStatus === null
    ? MISSING_DATA_LABEL
    : detail.applicationStatusLabel
  const missingContextLabels = [
    detail.rentalTypeLabel === MISSING_DATA_LABEL ? '임대유형' : null,
    statusLabel === MISSING_DATA_LABEL ? '접수상태' : null,
    detail.publicationTypeLabel === MISSING_DATA_LABEL ? '공고구분' : null,
  ].filter((label): label is string => label !== null)

  return (
    <header className={styles.header}>
      {backButton}
      <div className={styles.headerMain}>
        <div
          className={styles.headerContext}
          role="group"
          aria-label={missingContextLabels.length > 0
            ? `${missingContextLabels.join(' · ')} ${MISSING_DATA_LABEL}`
            : '공고 분류 및 접수 상태'}
        >
          {detail.rentalTypeLabel !== statusLabel && <span>{detail.rentalTypeLabel}</span>}
          <AnnouncementStatusBadge
            label={statusLabel}
            tone={statusTone(detail.applicationStatus)}
            countdown={hasCountdown ? {
              visible: deadlineLabel(detail),
              accessible: deadlineAccessibleLabel(detail),
            } : null}
          />
          {!hasCountdown && deadlineLabel(detail) !== statusLabel
            && deadlineLabel(detail) !== detail.rentalTypeLabel && (
            <span aria-label={deadlineAccessibleLabel(detail)}>{deadlineLabel(detail)}</span>
          )}
          {detail.publicationTypeLabel !== '원공고'
            && detail.publicationTypeLabel !== statusLabel
            && detail.publicationTypeLabel !== detail.rentalTypeLabel && (
            <span data-publication="changed">{detail.publicationTypeLabel}</span>
          )}
        </div>
        <h2 ref={headingRef} id={titleId} tabIndex={-1} title={title}>{title}</h2>
      </div>
      <DetailCloseButton label="공고 상세 닫기" onClose={onClose} />
    </header>
  )
}

function NoticeIntro({
  detail,
  groups,
}: {
  detail: HousingAnnouncementDetailData
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
}) {
  const firstGroup = groups[0]
  const remainingComplexes = Math.max(detail.supplyComplexCount - 1, 0)
  const agency = agencyLabel(detail)
  const region = regionLabel(detail.regionNames)
  const missingAgencyAndRegion = agency === MISSING_DATA_LABEL && region === MISSING_DATA_LABEL
  return (
    <section className={styles.intro} aria-label="공고 요약">
      <p>
        {missingAgencyAndRegion ? (
          <>
            <span className={styles.visuallyHidden}>공사·지역 </span>
            <span>{MISSING_DATA_LABEL}</span>
          </>
        ) : (
          <>
            <strong data-agency={detail.agencyCode}>{agency}</strong>
            <span aria-hidden="true">·</span>
            <b>{region}</b>
          </>
        )}
      </p>
      {firstGroup && (
        <p>
          <strong>{firstGroup.name}</strong>
          {remainingComplexes > 0 && <em>외 {remainingComplexes}곳</em>}
        </p>
      )}
      <small>{detail.recruitmentTypeLabel}</small>
    </section>
  )
}

function CoreInformation({ detail }: { detail: HousingAnnouncementDetailData }) {
  const audiences = detail.targets.map((target) => target.trim()).filter(Boolean)
  return (
    <DetailSection title="공고 핵심 정보">
      <DetailFacts>
        <DetailFact term="접수 시작" value={formatDate(detail.applicationStartAt)} />
        <DetailFact term="접수 마감" value={formatDate(detail.applicationEndAt)} />
        <DetailFact term="공급 단지" value={formatNullableCount(detail.supplyComplexCount, '개 단지')} />
        <DetailFact term="공급 세대" value={formatNullableCount(detail.supplyHouseholdCount, '세대')} />
        <DetailFact term="지역" value={regionLabel(detail.regionNames)} />
        <DetailFact term="공사" value={agencyLabel(detail)} />
        <DetailFact term="신청 대상" value={audiences.join(' · ') || MISSING_DATA_LABEL} wide />
      </DetailFacts>
      <div className={styles.meta}>
        <span>게시 {formatDate(detail.publishedAt)}</span>
        <span>조회 {detail.viewCount.toLocaleString('ko-KR')}</span>
      </div>
    </DetailSection>
  )
}

function ReasonNotice({ detail }: { detail: HousingAnnouncementDetailData }) {
  if (!hasText(detail.correctionOrCancellationReason)) {
    return null
  }
  return (
    <section className={styles.reason} aria-label={`${detail.publicationTypeLabel} 사유`}>
      <strong>{detail.publicationTypeLabel} 안내</strong>
      <p>{detail.correctionOrCancellationReason}</p>
    </section>
  )
}

function ScheduleSection({ detail, groups }: {
  detail: HousingAnnouncementDetailData
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
}) {
  const applicationSchedules = detail.applicationSchedules ?? []
  const hasVerifiedSchedules = applicationSchedules.length > 0
  const schedules = detail.schedules
  const legacyApplicationSchedules = schedules.filter((schedule) => schedule.type === 'APPLICATION')
  const hasApplicationSchedule = hasVerifiedSchedules || legacyApplicationSchedules.length > 0
  const hasWinnerSchedule = schedules.some(
    (schedule) => schedule.type === 'WINNER_ANNOUNCEMENT',
  )
  const hasApplicationFallback = !hasApplicationSchedule && (
    detail.applicationStartAt !== null || detail.applicationEndAt !== null
  )
  const hasWinnerDate = detail.winnerAnnouncementAt !== null && !hasWinnerSchedule
  const followUpSchedules = [
    ...schedules.filter((schedule) => schedule.type !== 'APPLICATION'),
    ...(hasWinnerDate ? [{ scheduleId: 'winner-fallback', type: 'WINNER_ANNOUNCEMENT',
      typeLabel: '당첨자 발표', name: null, startAt: detail.winnerAnnouncementAt, endAt: null }] : []),
  ]
  const receptionSchedules = hasApplicationFallback ? [{
    scheduleId: 'application-fallback', type: 'APPLICATION', typeLabel: '접수 기간', name: null,
    startAt: detail.applicationStartAt, endAt: detail.applicationEndAt,
  }] : legacyApplicationSchedules

  return (
    <>
      <DetailSection title="접수 일정">
        {hasVerifiedSchedules && <ApplicationScheduleGroups schedules={applicationSchedules} complexes={groups} />}
        {!hasApplicationSchedule && !hasApplicationFallback && <EmptyState>{MISSING_DATA_LABEL}</EmptyState>}
        {!hasVerifiedSchedules && receptionSchedules.length > 0 && (
          <AnnouncementScheduleGroups schedules={receptionSchedules} caption="접수 일정"
            current={hasApplicationFallback && detail.applicationStatus === 'APPLYING'} />
        )}
      </DetailSection>
      {followUpSchedules.length > 0 && (
        <DetailSection title="후속 일정">
          <AnnouncementScheduleGroups schedules={followUpSchedules} caption="후속 일정" />
        </DetailSection>
      )}
    </>
  )
}

function AnnouncementScheduleGroups({ schedules, caption, current = false }: {
  schedules: readonly HousingAnnouncementDetailSchedule[]
  caption: string
  current?: boolean
}) {
  return <div className={scheduleStyles.groups}>
    {groupAnnouncementSchedules(schedules).map((group) => (
      <ScheduleGroupCard key={group.key} name={group.name} targets={group.targets} caption={caption}>
        {group.schedules.map((schedule) => (
          <ScheduleItem key={schedule.scheduleId} label={schedule.name ?? schedule.typeLabel}
            startAt={schedule.startAt} endAt={schedule.endAt} current={current} />
        ))}
      </ScheduleGroupCard>
    ))}
  </div>
}

function ScheduleItem({
  label,
  startAt,
  endAt,
  current = false,
}: {
  label: string
  startAt: string | null
  endAt: string | null
  current?: boolean
}) {
  return (
    <li className={scheduleStyles.item} data-current={current || undefined} aria-current={current ? 'step' : undefined}>
      <header className={scheduleStyles.heading}>
        <h5>{label}</h5>
        {current && <span className={scheduleStyles.state} data-confirmed="true">현재 단계</span>}
      </header>
      <SchedulePeriod startAt={startAt} endAt={endAt} hideMidnight />
    </li>
  )
}

function ReceptionPlaces({
  places,
}: {
  places: readonly HousingAnnouncementDetailReceptionPlace[]
}) {
  if (places.length === 0) {
    return null
  }
  return (
    <DetailSection title="접수 방법">
      <ul className={styles.receptionList}>
        {places.map((place, index) => {
          const url = toHttpUrl(place.url)
          return (
            <li key={`${place.name ?? 'place'}-${index}`}>
              <div className={styles.receptionHeading}>
                <div className={styles.receptionIdentity}>
                  <strong>{place.name ?? MISSING_DATA_LABEL}</strong>
                  <span>{place.methodLabel}</span>
                </div>
                {url && <ExternalLink href={url}>접수처 열기</ExternalLink>}
              </div>
              {(hasText(place.address) || hasText(place.phoneNumber)) && (
                <DetailFacts>
                  {hasText(place.address) && <DetailFact term="주소" value={place.address} wide />}
                  {hasText(place.phoneNumber) && <DetailFact term="문의" value={formatPhoneNumber(place.phoneNumber) ?? MISSING_DATA_LABEL} wide />}
                </DetailFacts>
              )}
            </li>
          )
        })}
      </ul>
    </DetailSection>
  )
}

function ComplexComparison({
  groups,
  rentalTypeLabel,
  supplyComplexCount,
  supplyHouseholdCount,
  onOpenComplex,
}: {
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
  rentalTypeLabel: string
  supplyComplexCount: number
  supplyHouseholdCount: number | null
  onOpenComplex?: (complexId: string) => void
}) {
  return (
    <DetailSection
      title="단지 비교"
      aside={`${formatNullableCount(supplyComplexCount, '개 단지')} · ${supplyHouseholdSummary(supplyHouseholdCount)}`}
    >
      {groups.length === 0 && <EmptyState>단지 정보: {MISSING_DATA_LABEL}</EmptyState>}
      <div className={styles.complexList}>
        {groups.map((group) => (
          <ComplexCard
            key={group.key}
            group={group}
            rentalTypeLabel={rentalTypeLabel}
            onOpenComplex={onOpenComplex}
          />
        ))}
      </div>
    </DetailSection>
  )
}

function ComplexCard({
  group,
  rentalTypeLabel,
  onOpenComplex,
}: {
  group: HousingAnnouncementSupplyComplexGroup
  rentalTypeLabel: string
  onOpenComplex?: (complexId: string) => void
}) {
  const imageUrl = toHttpUrl(group.overviewImageUrl)
  const depositRange = moneyRange(group.rows, 'deposit')
  const monthlyRentRange = moneyRange(group.rows, 'monthlyRent')

  function openComplex() {
    if (!group.complexId || !onOpenComplex) {
      return
    }
    onOpenComplex(group.complexId)
  }

  return (
    <article className={styles.complexCard} aria-label={`${group.name} 단지 비교`}>
      <header className={styles.complexHeading}>
        <div>
          <strong>{group.name}</strong>
          <span>{rentalTypeLabel}</span>
        </div>
        {group.complexId && onOpenComplex && (
          <button
            type="button"
            data-detail-return-focus={`complex:${group.complexId}`}
            aria-label={`${group.name} 단지 상세 보기`}
            onClick={openComplex}
          >
            단지 상세
          </button>
        )}
      </header>
      <div className={styles.complexSummary} data-has-image={imageUrl !== null}>
        {imageUrl && (
          <div className={styles.complexImage}>
            <img src={imageUrl} alt={`${group.name} 단지 조감도`} loading="lazy" />
          </div>
        )}
        <div className={styles.complexBody}>
          <p>{group.address ?? `주소: ${MISSING_DATA_LABEL}`}</p>
          <div className={styles.complexCounts}>
            <span>총 <b>{formatNullableCount(group.totalHouseholdCount, '세대')}</b></span>
            <span>공급 <b>{formatNullableCount(group.supplyHouseholdCount, '세대')}</b></span>
          </div>
        </div>
      </div>
      <div className={styles.complexRanges}>
        <DetailFacts>
          <DetailFact term="전용면적" value={areaRange(group.rows)} wide />
          <DetailFact
            term="보증금"
            value={depositRange}
            wide={depositRange.includes(' – ')}
            emphasis={group.rows.some((row) => row.targets.some((target) => Number.isFinite(target.deposit)))}
          />
          <DetailFact
            term="월 임대료"
            value={monthlyRentRange}
            wide={monthlyRentRange.includes(' – ')}
            emphasis={group.rows.some((row) => row.targets.some((target) => Number.isFinite(target.monthlyRent)))}
          />
        </DetailFacts>
      </div>
    </article>
  )
}

function HousingTypeComparison({
  groups,
  selectedGroup,
  tabRefs,
  onSelectGroup,
  onGroupKeyDown,
  onOpenFloorPlan,
}: {
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
  selectedGroup: HousingAnnouncementSupplyComplexGroup
  tabRefs: Map<string, HTMLButtonElement>
  onSelectGroup: (group: HousingAnnouncementSupplyComplexGroup) => void
  onGroupKeyDown: (event: KeyboardEvent<HTMLButtonElement>, index: number) => void
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const idPrefix = useId()
  const selectedIndex = groups.findIndex((group) => (
    group.key === selectedGroup.key
  ))
  const panelId = `${idPrefix}-housing-types`
  return (
    <DetailSection
      title="주택형 비교"
    >
      <div className={styles.housingTypeGroup}>
        {groups.length > 0 && (
          <div className={styles.complexTabs} role="tablist" aria-label="주택형을 볼 단지 선택">
            {groups.map((group, index) => (
              <button
                key={group.key}
                ref={(node) => setTabRef(tabRefs, group.key, node)}
                id={`${idPrefix}-complex-tab-${index}`}
                type="button"
                role="tab"
                aria-controls={panelId}
                aria-selected={group.key === selectedGroup.key}
                tabIndex={group.key === selectedGroup.key ? 0 : -1}
                onClick={() => onSelectGroup(group)}
                onKeyDown={(event) => onGroupKeyDown(event, index)}
              >
                <span>{group.name}</span>
                <b>{supplyHouseholdSummary(group.supplyHouseholdCount)}</b>
              </button>
            ))}
          </div>
        )}
        <div
          className={styles.housingTypes}
          id={panelId}
          role="tabpanel"
          aria-labelledby={`${idPrefix}-complex-tab-${selectedIndex}`}
        >
          {selectedGroup.rows.map((row) => (
            <HousingTypeCard
              key={row.supplyRowId}
              complexName={selectedGroup.name}
              row={row}
              onOpenFloorPlan={onOpenFloorPlan}
            />
          ))}
        </div>
      </div>
    </DetailSection>
  )
}

function HousingTypeCard({
  complexName,
  row,
  onOpenFloorPlan,
}: {
  complexName: string
  row: HousingAnnouncementDetailSupplyRow
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const idPrefix = useId()
  const housingTypeName = row.housingType?.name
    ?? row.sourceHousingTypeName
    ?? MISSING_DATA_LABEL
  const twoDimensionalUrl = toHttpUrl(row.housingType?.floorPlanImageUrl ?? null)
  const threeDimensionalUrl = toHttpUrl(row.housingType?.floorPlan3dImageUrl ?? null)
  const hasFloorPlan = twoDimensionalUrl !== null || threeDimensionalUrl !== null

  return (
    <article className={styles.housingTypeCard} aria-label={`${complexName} ${housingTypeName} 주택형`}>
      <div className={styles.housingTypeHeading}>
        <div>
          <strong>{housingTypeName}</strong>
          <span>{row.supplyTypeLabel}</span>
        </div>
        {hasFloorPlan && (
          <button
            type="button"
            aria-label={`${complexName} ${housingTypeName} 평면도 보기`}
            onClick={() => onOpenFloorPlan({
              complexName,
              housingTypeName,
              threeDimensionalUrl,
              twoDimensionalUrl,
            })}
          >
            평면도 보기
          </button>
        )}
      </div>
      <DetailTable caption={`${complexName} ${housingTypeName} 공급 정보`}>
        <colgroup>
          <col style={{ width: '22%' }} /><col style={{ width: '28%' }} />
          <col style={{ width: '22%' }} /><col style={{ width: '28%' }} />
        </colgroup>
        <tbody>
          <tr>
            <th id={`${idPrefix}-kind`} scope="row">공급 구분</th>
            <td headers={`${idPrefix}-kind`}>{row.supplyTypeLabel}</td>
            <th id={`${idPrefix}-area`} scope="row">전용면적</th>
            <td headers={`${idPrefix}-area`}>{formatArea(row.housingType?.exclusiveArea ?? null)}</td>
          </tr>
          <tr>
            <th id={`${idPrefix}-count`} scope="row">공급 세대수</th>
            <td headers={`${idPrefix}-count`}>{formatNullableCount(row.totalSupplyHouseholdCount, '세대')}</td>
            <th id={`${idPrefix}-occupancy`} scope="row">입주 예정</th>
            <td headers={`${idPrefix}-occupancy`}>{formatYearMonth(row.occupancyExpectedYearMonth)}</td>
          </tr>
        </tbody>
      </DetailTable>
      <SupplyTargets targets={row.targets} />
    </article>
  )
}

function SupplyTargets({ targets }: { targets: readonly AnnouncementSupplyTarget[] }) {
  const idPrefix = useId()
  const visibleTargets = targets.filter((target) => (
    [target.target, target.priority, target.applicationCondition].some(hasText)
    || [target.supplyHouseholdCount, target.waitlistCount, target.deposit, target.monthlyRent].some(Number.isFinite)
  ))
  if (visibleTargets.length === 0) return null
  return (
    <ul className={styles.targetList} aria-label="대상별 공급 조건">
      {visibleTargets.map((target, index) => (
        <li key={target.supplyTargetId}>
          <header className={styles.targetHeading}>
            <strong>{target.target ?? MISSING_DATA_LABEL}</strong>
            {hasText(target.priority) && <span>{target.priority}</span>}
          </header>
          <DetailTable caption={`${target.target ?? MISSING_DATA_LABEL} 공급 조건`}>
            <colgroup>
              <col style={{ width: '22%' }} /><col style={{ width: '28%' }} />
              <col style={{ width: '22%' }} /><col style={{ width: '28%' }} />
            </colgroup>
            <tbody>
              <tr>
                <th id={`${idPrefix}-${index}-count`} scope="row">공급 세대수</th>
                <td headers={`${idPrefix}-${index}-count`} data-numeric="true">
                  {formatNullableCount(target.supplyHouseholdCount, '세대')}
                </td>
                <th id={`${idPrefix}-${index}-waitlist`} scope="row">모집 예비자 수</th>
                <td headers={`${idPrefix}-${index}-waitlist`} data-numeric="true">
                  {formatNullableCount(target.waitlistCount, '명')}
                </td>
              </tr>
              <tr>
                <th id={`${idPrefix}-${index}-deposit`} scope="row">보증금</th>
                <td headers={`${idPrefix}-${index}-deposit`} data-emphasis={Number.isFinite(target.deposit) || undefined} data-numeric="true">
                  {formatHousingMoneyWon(target.deposit)}
                </td>
                <th id={`${idPrefix}-${index}-rent`} scope="row">월 임대료</th>
                <td headers={`${idPrefix}-${index}-rent`} data-emphasis={Number.isFinite(target.monthlyRent) || undefined} data-numeric="true">
                  {formatHousingMoneyWon(target.monthlyRent)}
                </td>
              </tr>
              {hasText(target.applicationCondition) && (
                <tr>
                  <th id={`${idPrefix}-${index}-condition`} scope="row">신청 조건</th>
                  <td headers={`${idPrefix}-${index}-condition`} colSpan={3}>{target.applicationCondition}</td>
                </tr>
              )}
            </tbody>
          </DetailTable>
        </li>
      ))}
    </ul>
  )
}

function DocumentActions({ detail, onOpenAttachments }: {
  detail: HousingAnnouncementDetailData
  onOpenAttachments: () => void
}) {
  const sourceUrl = toHttpUrl(detail.documentLinkUrl)
  const hasFiles = detail.attachments.some(hasAttachmentUrl)
  return (
    <footer className={styles.documents}>
      <nav aria-label="공고문 바로가기">
        <button type="button" disabled={!hasFiles} onClick={onOpenAttachments}>공고문 보기</button>
        {sourceUrl && <ExternalLink href={sourceUrl}>공고 원문</ExternalLink>}
        {!sourceUrl && <DisabledLink>공고 원문</DisabledLink>}
      </nav>
    </footer>
  )
}

function FloorPlanDialog({
  selection,
  onClose,
}: {
  selection: FloorPlanSelection
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const titleId = `announcement-floor-plan-${safeId(selection.complexName)}-${safeId(selection.housingTypeName)}`

  useEffect(() => {
    returnFocusRef.current = document.activeElement as HTMLElement | null
    dialogRef.current?.querySelector<HTMLElement>('button')?.focus()
    return () => returnFocusRef.current?.focus()
  }, [])

  return (
    <div
      className={styles.modalLayer}
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <section
        ref={dialogRef}
        className={styles.modal}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={(event) => handleDialogKeyDown(event, onClose)}
      >
        <header>
          <div>
            <span>{selection.complexName}</span>
            <h2 id={titleId}>{selection.housingTypeName} 평면도</h2>
          </div>
          <DetailCloseButton label="평면도 닫기" onClose={onClose} />
        </header>
        <div className={styles.floorPlans}>
          {selection.twoDimensionalUrl && (
            <figure>
              <img src={selection.twoDimensionalUrl} alt={`${selection.housingTypeName} 2D 평면도`} />
              <figcaption>2D 평면도</figcaption>
            </figure>
          )}
          {selection.threeDimensionalUrl && (
            <figure>
              <img src={selection.threeDimensionalUrl} alt={`${selection.housingTypeName} 3D 평면도`} />
              <figcaption>3D 평면도</figcaption>
            </figure>
          )}
        </div>
      </section>
    </div>
  )
}

function EmptyState({ children }: { children: ReactNode }) {
  return <p className={styles.empty}>{children}</p>
}

function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noreferrer">{children}</a>
  )
}

function DisabledLink({ children }: { children: ReactNode }) {
  return <span className={styles.disabledLink} aria-disabled="true">{children}</span>
}

function deadlineLabel(detail: HousingAnnouncementDetailData) {
  if (detail.applicationStatus === 'CONDITIONAL') {
    return '조건부 접수'
  }
  if (detail.applicationStatus === 'CANCELLED') {
    return '공고 취소'
  }
  if (detail.applicationStatus === 'CLOSED') {
    return '접수 마감'
  }
  if (detail.dDay === null || !Number.isInteger(detail.dDay)) {
    return MISSING_DATA_LABEL
  }
  if (detail.applicationStatus === 'BEFORE_APPLICATION' && detail.dDay >= 0) {
    return `접수 시작 ${detail.dDay === 0 ? 'D-Day' : `D-${detail.dDay}`}`
  }
  if (detail.dDay === 0) {
    return 'D-day'
  }
  if (detail.dDay < 0) {
    return '접수 마감'
  }
  return `D-${detail.dDay}`
}

function deadlineAccessibleLabel(detail: HousingAnnouncementDetailData) {
  if (detail.applicationStatus === 'CONDITIONAL') {
    return '조건부 접수'
  }
  if (detail.applicationStatus === 'CANCELLED') {
    return '공고 취소'
  }
  if (detail.applicationStatus === 'CLOSED' || (detail.dDay !== null && detail.dDay < 0)) {
    return '접수 마감'
  }
  if (detail.dDay === null || !Number.isInteger(detail.dDay)) {
    return MISSING_DATA_LABEL
  }
  if (detail.applicationStatus === 'BEFORE_APPLICATION') {
    return detail.dDay === 0 ? '접수 시작일 당일' : `접수 시작까지 ${detail.dDay}일`
  }
  if (detail.dDay === 0) {
    return '접수 마감일'
  }
  return `접수 마감까지 ${detail.dDay}일`
}

function statusTone(value: string | null) {
  if (value === 'APPLYING') {
    return 'applying'
  }
  if (value === 'BEFORE_APPLICATION') {
    return 'upcoming'
  }
  if (value === 'CANCELLED') {
    return 'cancelled'
  }
  return 'closed'
}

function agencyLabel(detail: HousingAnnouncementDetailData) {
  return detail.agencyCode ?? detail.agencyName ?? MISSING_DATA_LABEL
}

function regionLabel(regions: readonly string[]) {
  return regions.length > 0 ? regions.join(' · ') : MISSING_DATA_LABEL
}

function formatDate(value: string | null) {
  if (!value) {
    return MISSING_DATA_LABEL
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (!match) {
    return MISSING_DATA_LABEL
  }
  return `${match[1]}.${match[2]}.${match[3]}`
}

function formatYearMonth(value: string | null) {
  if (!value) {
    return MISSING_DATA_LABEL
  }
  const match = /^(\d{4})-?(\d{2})/.exec(value)
  if (!match) {
    return MISSING_DATA_LABEL
  }
  return `${match[1]}.${match[2]}`
}

function formatNullableCount(value: number | null, unit: string) {
  if (value === null) {
    return MISSING_DATA_LABEL
  }
  return `${value.toLocaleString('ko-KR')}${unit}`
}

function supplyHouseholdSummary(value: number | null) {
  return value === null ? `공급 세대수: ${MISSING_DATA_LABEL}` : formatNullableCount(value, '세대')
}

function formatArea(value: number | null) {
  if (value === null) {
    return MISSING_DATA_LABEL
  }
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}㎡`
}

function areaRange(rows: readonly HousingAnnouncementDetailSupplyRow[]) {
  const values = rows
    .map((row) => row.housingType?.exclusiveArea ?? null)
    .filter((value): value is number => value !== null)
  return numericRange(values, (value) => formatArea(value))
}

function moneyRange(
  rows: readonly HousingAnnouncementDetailSupplyRow[],
  key: 'deposit' | 'monthlyRent',
) {
  const values = rows.flatMap((row) => row.targets)
    .map((target) => target[key])
    .filter((value): value is number => value !== null)
  return numericRange(values, formatHousingMoneyWon)
}

function numericRange(
  values: readonly number[],
  format: (value: number) => string,
) {
  if (values.length === 0) {
    return MISSING_DATA_LABEL
  }
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  if (minimum === maximum) {
    return format(minimum)
  }
  return `${format(minimum)} – ${format(maximum)}`
}

function tabIndexForKey(key: string, currentIndex: number, length: number) {
  if (key === 'Home') {
    return 0
  }
  if (key === 'End') {
    return length - 1
  }
  if (key === 'ArrowRight') {
    return (currentIndex + 1) % length
  }
  if (key === 'ArrowLeft') {
    return (currentIndex - 1 + length) % length
  }
  return null
}

function setTabRef(
  refs: Map<string, HTMLButtonElement>,
  key: string,
  node: HTMLButtonElement | null,
) {
  if (node) {
    refs.set(key, node)
    return
  }
  refs.delete(key)
}

function handleDialogKeyDown(
  event: KeyboardEvent<HTMLElement>,
  onClose: () => void,
) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    onClose()
    return
  }
  if (event.key !== 'Tab') {
    return
  }
  const focusable = event.currentTarget.querySelectorAll<HTMLElement>(
    'button:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])',
  )
  if (focusable.length === 0) {
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const wrapsBackward = event.shiftKey && document.activeElement === first
  const wrapsForward = !event.shiftKey && document.activeElement === last
  if (!wrapsBackward && !wrapsForward) {
    return
  }
  event.preventDefault()
  if (wrapsBackward) {
    last?.focus()
  }
  if (wrapsForward) {
    first?.focus()
  }
}

function safeId(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, '-')
}

function hasText(value: string | null): value is string {
  return value !== null && value.trim().length > 0
}
