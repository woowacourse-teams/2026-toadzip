import { captureProductEvent } from '../../analytics/productAnalytics'
import { detailVisitId, useDetailScrollAnalytics } from './detailAnalytics'
import { toHttpUrl } from '../presentation/httpUrl.ts'
import {
  type KeyboardEvent,
  type PointerEvent,
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
  DetailTable,
  DetailSection,
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
import { formatHousingArea as formatArea, type AreaUnit } from '../presentation/housingArea'
import { useAreaUnit } from '../presentation/useAreaUnit'
import { AreaUnitToggle } from './AreaUnitToggle'
import { formatPhoneNumber } from '../presentation/phoneNumber'
import { groupAnnouncementSchedules } from '../presentation/announcementSchedulePresentation'
import { ScheduleGroupCard } from './ScheduleGroupCard'
import { SchedulePeriod } from './SchedulePeriod'
import scheduleStyles from './ScheduleGroups.module.css'
import { formatHousingMoneyReadable } from '../presentation/housingMoney'
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
  readonly exclusiveArea?: number | null
  readonly supplyArea?: number | null
  readonly corrected?: boolean
  readonly sharedRecruitment?: { readonly id: number; readonly householdCount: number | null } | null
  readonly supplyRowId: string
  readonly sourceComplexName: string | null
  readonly sourceHousingTypeName: string | null
  readonly displayHousingTypeName?: string | null
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
  const scrollAnalytics = useDetailScrollAnalytics('ANNOUNCEMENT', detail.announcementId)
  const { areaUnit, toggleAreaUnit } = useAreaUnit()
  const sectionId = useId()
  const { scrollRef, sectionRefs, activeSection, trackSection, scrollToSection } =
    useAnnouncementSections(detail.announcementId)
  const groups = useMemo(
    () => groupAnnouncementSupplyRows(detail.supplyRows),
    [detail.supplyRows],
  )
  const [floorPlan, setFloorPlan] = useState<FloorPlanSelection | null>(null)
  const [attachmentAnnouncementId, setAttachmentAnnouncementId] = useState<string | null>(null)
  const announcementFiles = detail.attachments.filter((attachment) =>
    attachment.fileTypeLabel.includes('공고문') && hasAttachmentUrl(attachment))
  const primaryAttachment = announcementFiles.find((attachment) => /\.pdf$/i.test(attachment.fileName?.trim() ?? ''))
    ?? announcementFiles.find((attachment) => /\.hwpx?$/i.test(attachment.fileName?.trim() ?? ''))
    ?? announcementFiles[0]
  const title = detail.title ?? MISSING_DATA_LABEL
  const titleId = `announcement-detail-title-${detail.announcementId}`
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true })
  }, [detail.announcementId])

  function handlePanelKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape') {
      return
    }
    event.stopPropagation()
    onClose()
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
        {...scrollAnalytics}
        onScroll={(event) => { scrollAnalytics.onScroll(event); trackSection() }}
        className={styles.scroll}
        role="region"
        aria-label={`${title} 상세 내용`}
        tabIndex={0}
      >
        <div id={`${sectionId}-summary`} className={styles.sectionGroup}
          role="region" aria-label="요약 영역" tabIndex={-1}
          ref={(node) => { sectionRefs.current.summary = node }}>
          <div className={styles.summaryBlock}>
            <NoticeIntro detail={detail} groups={groups} />
            <div className={styles.notificationAction}>
              <NotificationInterestButton
                target={{ type: 'ANNOUNCEMENT', id: detail.announcementId, name: title }}
                source="ANNOUNCEMENT_DETAIL"
              />
            </div>
          </div>
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
            areaUnit={areaUnit}
            onToggleAreaUnit={toggleAreaUnit}
            groups={groups}
            rentalTypeLabel={detail.rentalTypeLabel}
            supplyComplexCount={detail.supplyComplexCount}
            supplyHouseholdCount={detail.supplyHouseholdCount}
            onOpenComplex={onOpenComplex}
          />
          {groups.length > 0 && (
            <HousingTypeComparison
              key={`housing-types:${detail.announcementId}`}
              areaUnit={areaUnit}
              onToggleAreaUnit={toggleAreaUnit}
              groups={groups}
              onOpenFloorPlan={setFloorPlan}
            />
          )}
          <AttachmentList key={detail.announcementId} announcementId={detail.announcementId} attachments={detail.attachments} />
        </div>
      </div>

      <DocumentActions detail={detail} hasAnnouncementFile={primaryAttachment !== undefined}
        onOpenAttachments={() => setAttachmentAnnouncementId(detail.announcementId)} />
      {attachmentAnnouncementId === detail.announcementId && primaryAttachment && (
        <AttachmentDialog key={detail.announcementId} announcementId={detail.announcementId} attachments={[primaryAttachment]}
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
          {/* 원공고는 표시하지 않고, 정정·취소 공고만 빨간 딱지로 알린다. */}
          {publicationBadge(detail.publicationTypeLabel) && (
            <span data-publication="changed">{publicationBadge(detail.publicationTypeLabel)}</span>
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
      <p className={styles.introMeta}>
        {missingAgencyAndRegion ? (
          <>
            <span className={styles.visuallyHidden}>공사·지역 </span>
            <span>{MISSING_DATA_LABEL}</span>
          </>
        ) : (
          <>
            <strong data-agency={detail.agencyCode}>{agency}</strong>
            <span aria-hidden="true">{'\u00a0·\u00a0'}</span>
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
    <DetailSection appearance="block" title="공고 핵심 정보">
      <DetailFacts appearance="list">
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
      <DetailSection appearance="block" title="접수 일정">
        {hasVerifiedSchedules && <ApplicationScheduleGroups schedules={applicationSchedules} complexes={groups} />}
        {!hasApplicationSchedule && !hasApplicationFallback && <EmptyState>{MISSING_DATA_LABEL}</EmptyState>}
        {!hasVerifiedSchedules && receptionSchedules.length > 0 && (
          <AnnouncementScheduleGroups schedules={receptionSchedules} caption="접수 일정"
            current={hasApplicationFallback && detail.applicationStatus === 'APPLYING'} />
        )}
      </DetailSection>
      {followUpSchedules.length > 0 && (
        <DetailSection appearance="block" title="후속 일정">
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
    <DetailSection appearance="block" title="접수 방법">
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
                <DetailFacts appearance="list">
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
  areaUnit,
  onToggleAreaUnit,
  groups,
  rentalTypeLabel,
  supplyComplexCount,
  supplyHouseholdCount,
  onOpenComplex,
}: {
  areaUnit: AreaUnit
  onToggleAreaUnit: () => void
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
  rentalTypeLabel: string
  supplyComplexCount: number
  supplyHouseholdCount: number | null
  onOpenComplex?: (complexId: string) => void
}) {
  return (
    <DetailSection appearance="block"
      title="단지 비교"
      actions={groups.length > 0 ? <AreaUnitToggle unit={areaUnit} onToggle={onToggleAreaUnit} /> : undefined}
      aside={`${formatNullableCount(supplyComplexCount, '개 단지')} · ${supplyHouseholdSummary(supplyHouseholdCount)}`}
    >
      {groups.length === 0 && <EmptyState>단지 정보: {MISSING_DATA_LABEL}</EmptyState>}
      <div className={styles.complexList}>
        {groups.map((group) => (
          <ComplexCard
            areaUnit={areaUnit}
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
  areaUnit,
  group,
  rentalTypeLabel,
  onOpenComplex,
}: {
  areaUnit: AreaUnit
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
          <DetailFact term="전용면적" value={areaRange(group.rows, areaUnit)} wide />
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
      {group.complexId && onOpenComplex && (
        <button
          className={styles.complexDetailButton}
          type="button"
          data-detail-return-focus={`complex:${group.complexId}`}
          aria-label={`${group.name} 단지 상세 보기`}
          onClick={openComplex}
        >
          단지 상세 보기 <span aria-hidden="true">→</span>
        </button>
      )}
    </article>
  )
}

function HousingTypeComparison({
  areaUnit,
  onToggleAreaUnit,
  groups,
  onOpenFloorPlan,
}: {
  areaUnit: AreaUnit
  onToggleAreaUnit: () => void
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const id = useId()
  const [selectedKey, setSelectedKey] = useState(groups[0]?.key)
  const tabListRef = useRef<HTMLDivElement>(null)
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])
  const [scrollEdges, setScrollEdges] = useState({ left: false, right: false })
  const [dragging, setDragging] = useState(false)
  const dragRef = useRef<{ pointerId: number; x: number; scrollLeft: number; moved: boolean } | null>(null)
  const suppressClickRef = useRef(false)
  const updateScrollEdges = useCallback(() => {
    const list = tabListRef.current
    if (!list) return
    const left = list.scrollLeft > 1
    const right = list.scrollLeft + list.clientWidth < list.scrollWidth - 1
    setScrollEdges((previous) => previous.left === left && previous.right === right ? previous : { left, right })
  }, [])

  useEffect(() => {
    const list = tabListRef.current
    if (!list) return
    updateScrollEdges()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(updateScrollEdges)
    observer.observe(list)
    tabRefs.current.forEach((tab) => { if (tab) observer.observe(tab) })
    return () => observer.disconnect()
  }, [groups, updateScrollEdges])

  const selectedIndex = Math.max(0, groups.findIndex((group) => group.key === selectedKey))
  const selectedGroup = groups[selectedIndex]

  useEffect(() => {
    const list = tabListRef.current
    const tab = tabRefs.current[selectedIndex]
    if (!list || !tab) return
    // Only scroll the horizontal strip; keep the detail panel's vertical position.
    const listBounds = list.getBoundingClientRect()
    const tabBounds = tab.getBoundingClientRect()
    if (tabBounds.left < listBounds.left) list.scrollLeft -= listBounds.left - tabBounds.left
    else if (tabBounds.right > listBounds.right) list.scrollLeft += tabBounds.right - listBounds.right
    updateScrollEdges()
  }, [selectedIndex, updateScrollEdges])

  function startTabDrag(event: PointerEvent<HTMLDivElement>) {
    suppressClickRef.current = false
    const list = event.currentTarget
    if (event.pointerType !== 'mouse' || event.button !== 0 || list.scrollWidth <= list.clientWidth) return
    dragRef.current = { pointerId: event.pointerId, x: event.clientX, scrollLeft: list.scrollLeft, moved: false }
  }

  function moveTabDrag(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    if ((event.buttons & 1) === 0) {
      endTabDrag(event, true)
      return
    }
    const distance = event.clientX - drag.x
    if (!drag.moved && Math.abs(distance) < 6) return
    if (!drag.moved) {
      drag.moved = true
      suppressClickRef.current = true
      event.currentTarget.setPointerCapture?.(event.pointerId)
      setDragging(true)
    }
    event.preventDefault()
    event.currentTarget.scrollLeft = drag.scrollLeft - distance
    updateScrollEdges()
  }

  function endTabDrag(event: PointerEvent<HTMLDivElement>, cancelled = false) {
    if (dragRef.current?.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragging(false)
    if (cancelled) suppressClickRef.current = false
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  function selectGroup(index: number, focus = false) {
    const group = groups[index]
    if (!group) return
    setSelectedKey(group.key)
    if (focus) tabRefs.current[index]?.focus({ preventScroll: true })
  }

  function handleTabKey(event: KeyboardEvent<HTMLButtonElement>) {
    let index: number
    if (event.key === 'ArrowRight') index = (selectedIndex + 1) % groups.length
    else if (event.key === 'ArrowLeft') index = (selectedIndex - 1 + groups.length) % groups.length
    else if (event.key === 'Home') index = 0
    else if (event.key === 'End') index = groups.length - 1
    else return
    event.preventDefault()
    selectGroup(index, true)
  }

  if (!selectedGroup) return null
  return (
    <DetailSection appearance="block"
      title="주택형 비교"
      actions={<AreaUnitToggle unit={areaUnit} onToggle={onToggleAreaUnit} />}
    >
      {groups.length > 1 && (
        <div className={styles.complexTabsFrame} data-scroll-left={scrollEdges.left} data-scroll-right={scrollEdges.right}>
          <div className={styles.complexTabs} role="tablist" aria-label="주택형 비교 단지 선택" ref={tabListRef}
            data-dragging={dragging || undefined} onScroll={updateScrollEdges}
            onPointerDown={startTabDrag} onPointerMove={moveTabDrag}
            onPointerUp={(event) => endTabDrag(event)} onPointerCancel={(event) => endTabDrag(event, true)}
            onLostPointerCapture={(event) => endTabDrag(event)}
            onClickCapture={(event) => {
              if (!suppressClickRef.current || event.detail === 0) return
              suppressClickRef.current = false
              event.preventDefault()
              event.stopPropagation()
            }}>
            {groups.map((group, index) => (
              <button key={group.key} type="button" role="tab" id={`${id}-tab-${index}`}
                className={styles.complexTab} ref={(node) => { tabRefs.current[index] = node }}
                aria-selected={index === selectedIndex} aria-controls={`${id}-panel-${index}`}
                tabIndex={index === selectedIndex ? 0 : -1}
                onClick={() => selectGroup(index)} onKeyDown={handleTabKey}>
                <strong>{group.name}</strong>
                <span>{supplyHouseholdSummary(group.supplyHouseholdCount)}</span>
              </button>
            ))}
          </div>
        </div>
      )}
      {groups.map((group, index) => (
        <section key={group.key} id={`${id}-panel-${index}`} className={styles.housingTypeGroup}
          role={groups.length > 1 ? 'tabpanel' : 'region'} hidden={index !== selectedIndex}
          aria-labelledby={groups.length > 1 ? `${id}-tab-${index}` : undefined}
          aria-label={groups.length === 1 ? `${group.name} 주택형 비교` : undefined}
          tabIndex={groups.length > 1 ? 0 : undefined}>
          {groups.length === 1 && (
            <header className={styles.housingTypeGroupHeading}>
              <h4>{group.name}</h4>
              <span>{supplyHouseholdSummary(group.supplyHouseholdCount)}</span>
            </header>
          )}
          {index === selectedIndex && <HousingTypeSelection key={group.key} group={group}
            areaUnit={areaUnit} onOpenFloorPlan={onOpenFloorPlan} />}
        </section>
      ))}
    </DetailSection>
  )
}

function HousingTypeSelection({ group, areaUnit, onOpenFloorPlan }: {
  group: HousingAnnouncementSupplyComplexGroup
  areaUnit: AreaUnit
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const [selectedId, setSelectedId] = useState(group.rows[0]?.supplyRowId)
  const selected = group.rows.find((row) => row.supplyRowId === selectedId) ?? group.rows[0]
  const id = useId()
  if (!selected) return null
  return (
    <div className={styles.housingTypes}>
      {group.rows.length > 1 && (
        <div>
          <p className={styles.housingTypeHint} id={`${id}-hint`}>
            주택형 {group.rows.length}개 · 선택하면 아래에 상세가 표시돼요
          </p>
          <div className={styles.housingTypeSummaryScroll} role="region"
            aria-label={`${group.name} 주택형 요약 목록`} tabIndex={group.rows.length > 5 ? 0 : undefined}>
            <table className={styles.housingTypeSummary} aria-label={`${group.name} 주택형 요약`}
              aria-describedby={`${id}-hint`}>
              <thead><tr><th scope="col">주택형</th><th scope="col">전용면적</th><th scope="col">공급세대</th></tr></thead>
              <tbody>
                {group.rows.map((row) => (
                  <tr key={row.supplyRowId} data-selected={row.supplyRowId === selected.supplyRowId}>
                    <td>
                      <button type="button" aria-label={`${housingTypeLabel(row)} 주택형 선택`}
                        aria-pressed={row.supplyRowId === selected.supplyRowId}
                        aria-controls={`${id}-selected`} onClick={() => setSelectedId(row.supplyRowId)}>
                        <strong>{housingTypeLabel(row)}</strong>
                        <span>{row.supplyTypeLabel}</span>
                      </button>
                    </td>
                    <td>{formatArea(row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea, areaUnit)}</td>
                    <td>{row.sharedRecruitment ? '공통 모집' : formatNullableCount(row.totalSupplyHouseholdCount, '세대')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
      <div id={`${id}-selected`}>
        {group.rows.length > 1 && <h4 className={styles.selectedHousingTypeTitle}>선택 주택형 상세</h4>}
        <HousingTypeCard areaUnit={areaUnit} complexName={group.name} row={selected}
          onOpenFloorPlan={onOpenFloorPlan} />
      </div>
    </div>
  )
}

function HousingTypeCard({
  areaUnit,
  complexName,
  row,
  onOpenFloorPlan,
}: {
  areaUnit: AreaUnit
  complexName: string
  row: HousingAnnouncementDetailSupplyRow
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const idPrefix = useId()
  const housingTypeName = housingTypeLabel(row)
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
            <td headers={`${idPrefix}-area`}>{formatArea(row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea, areaUnit)}</td>
          </tr>
          <tr>
            <th id={`${idPrefix}-count`} scope="row">공급 세대수</th>
            <td headers={`${idPrefix}-count`}>{row.sharedRecruitment ? '공통 합계 · 개별 미확정' : formatNullableCount(row.totalSupplyHouseholdCount, '세대')}</td>
            <th id={`${idPrefix}-occupancy`} scope="row">최초 입주 연월</th>
            <td headers={`${idPrefix}-occupancy`}>{formatYearMonth(row.occupancyExpectedYearMonth)}</td>
          </tr>
          {(row.supplyArea !== undefined || row.housingType?.supplyArea != null) && (
            <tr>
              <th id={`${idPrefix}-supply-area`} scope="row">공급면적</th>
              <td headers={`${idPrefix}-supply-area`} colSpan={3}>
                {formatArea(row.supplyArea === undefined ? row.housingType?.supplyArea ?? null : row.supplyArea, areaUnit)}
              </td>
            </tr>
          )}
        </tbody>
      </DetailTable>
      {row.occupancyExpectedYearMonth && (
        <p className={styles.occupancyNote}>
          최초 입주 연월은 원문 기준이며, 이번 모집의 입주 일정과 다를 수 있어요.
        </p>
      )}
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
                  {formatHousingMoneyReadable(target.deposit)}
                </td>
                <th id={`${idPrefix}-${index}-rent`} scope="row">월 임대료</th>
                <td headers={`${idPrefix}-${index}-rent`} data-emphasis={Number.isFinite(target.monthlyRent) || undefined} data-numeric="true">
                  {formatHousingMoneyReadable(target.monthlyRent)}
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

function housingTypeLabel(row: HousingAnnouncementDetailSupplyRow) {
  // 공고 표시명 → 단지 주택형명 → 원천 순서는 서버가 정한다.
  return row.displayHousingTypeName ?? row.housingType?.name ?? row.sourceHousingTypeName ?? MISSING_DATA_LABEL
}

function DocumentActions({ detail, hasAnnouncementFile, onOpenAttachments }: {
  detail: HousingAnnouncementDetailData
  hasAnnouncementFile: boolean
  onOpenAttachments: () => void
}) {
  const sourceUrl = toHttpUrl(detail.documentLinkUrl)
  return (
    <footer className={styles.documents}>
      <nav aria-label="공고문 바로가기">
        <button type="button" disabled={!hasAnnouncementFile} onClick={onOpenAttachments}>공고문 보기</button>
        {sourceUrl && <a href={sourceUrl} target="_blank" rel="noreferrer" onClick={(event) => {
          captureProductEvent('announcement_source_clicked', { announcement_id: detail.announcementId,
            detail_visit_id: detailVisitId(event.currentTarget) })
        }}>공고 사이트로 이동</a>}
        {!sourceUrl && <DisabledLink>공고 사이트로 이동</DisabledLink>}
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

function areaRange(rows: readonly HousingAnnouncementDetailSupplyRow[], unit: AreaUnit) {
  const values = rows
    .map((row) => row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea)
    .filter((value): value is number => value !== null && Number.isFinite(value))
  return numericRange(values, (value) => formatArea(value, unit))
}

function moneyRange(
  rows: readonly HousingAnnouncementDetailSupplyRow[],
  key: 'deposit' | 'monthlyRent',
) {
  const values = rows.flatMap((row) => row.targets)
    .map((target) => target[key])
    .filter((value): value is number => value !== null)
  return numericRange(values, formatHousingMoneyReadable)
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

function publicationBadge(label: string) {
  if (label === '정정공고') return '정정'
  if (label === '취소공고') return '취소'
  return null
}

function safeId(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, '-')
}

function hasText(value: string | null): value is string {
  return value !== null && value.trim().length > 0
}
