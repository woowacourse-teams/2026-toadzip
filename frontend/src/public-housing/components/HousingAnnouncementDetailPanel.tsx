import { captureProductEvent } from '../../analytics/productAnalytics'
import { detailVisitId, useDetailScrollAnalytics } from './detailAnalytics'
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
          <SupplyOverview
            areaUnit={areaUnit}
            onToggleAreaUnit={toggleAreaUnit}
            groups={groups}
            supplyComplexCount={detail.supplyComplexCount}
            supplyHouseholdCount={detail.supplyHouseholdCount}
            onOpenComplex={onOpenComplex}
            onOpenFloorPlan={setFloorPlan}
          />
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

/**
 * 공고의 단지·주택형은 자세히 읽기보다 한눈에 훑는 정보다.
 * 단지마다 이름 한 줄과 주택형 표 하나로 보여 주고, 평면도·대상별 조건은 접어 둔다.
 */
function SupplyOverview({
  areaUnit,
  onToggleAreaUnit,
  groups,
  supplyComplexCount,
  supplyHouseholdCount,
  onOpenComplex,
  onOpenFloorPlan,
}: {
  areaUnit: AreaUnit
  onToggleAreaUnit: () => void
  groups: readonly HousingAnnouncementSupplyComplexGroup[]
  supplyComplexCount: number
  supplyHouseholdCount: number | null
  onOpenComplex?: (complexId: string) => void
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  return (
    <DetailSection appearance="block"
      title="공급 주택형"
      actions={groups.length > 0 ? <AreaUnitToggle unit={areaUnit} onToggle={onToggleAreaUnit} /> : undefined}
      aside={`${formatNullableCount(supplyComplexCount, '개 단지')} · ${supplyHouseholdSummary(supplyHouseholdCount)}`}
    >
      {groups.length === 0 && <EmptyState>단지 정보: {MISSING_DATA_LABEL}</EmptyState>}
      {groups.map((group) => (
        <SupplyComplexTable
          key={group.key}
          areaUnit={areaUnit}
          group={group}
          onOpenComplex={onOpenComplex}
          onOpenFloorPlan={onOpenFloorPlan}
        />
      ))}
    </DetailSection>
  )
}

function SupplyComplexTable({
  areaUnit,
  group,
  onOpenComplex,
  onOpenFloorPlan,
}: {
  areaUnit: AreaUnit
  group: HousingAnnouncementSupplyComplexGroup
  onOpenComplex?: (complexId: string) => void
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const complexId = group.complexId
  // 입주 예정이 모든 주택형에 같으면 표 아래 한 줄로, 다르면 주택형마다 보여 준다.
  const occupancies = new Set(group.rows.map((row) => row.occupancyExpectedYearMonth))
  const sharedOccupancy = occupancies.size === 1 ? group.rows[0]?.occupancyExpectedYearMonth ?? null : null
  return (
    <section className={styles.supplyComplex} aria-label={group.name}>
      {/* 단지 이름 옆에 세대수를 두고, 단지 상세로 가는 길은 오른쪽 버튼으로 분명하게 보여 준다. */}
      <header className={styles.supplyComplexHead}>
        <div>
          <strong>{group.name}</strong>
          <span>{supplyHouseholdSummary(group.supplyHouseholdCount)}</span>
        </div>
        {complexId && onOpenComplex && (
          <button
            className={styles.complexDetailButton}
            type="button"
            data-detail-return-focus={`complex:${complexId}`}
            aria-label={`${group.name} 단지 상세 보기`}
            onClick={() => onOpenComplex(complexId)}
          >
            단지 상세
          </button>
        )}
      </header>
      <div className={styles.supplyTable}>
        <DetailTable caption={`${group.name} 주택형별 공급`}>
          <colgroup>
            <col style={{ width: '34%' }} />
            <col style={{ width: '20%' }} />
            <col style={{ width: '14%' }} />
            <col style={{ width: '32%' }} />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">주택형</th>
              <th scope="col">전용</th>
              <th scope="col">세대</th>
              <th scope="col">보증금<br />월 임대료</th>
            </tr>
          </thead>
          <tbody>
            {group.rows.map((row) => (
              <SupplyTableRow key={row.supplyRowId} areaUnit={areaUnit} row={row} showOccupancy={occupancies.size > 1} />
            ))}
          </tbody>
        </DetailTable>
      </div>
      {sharedOccupancy && <p className={styles.supplyNote}>입주 예정 {formatYearMonth(sharedOccupancy)}</p>}
      <details className={styles.supplyDetails}>
        <summary>평면도·대상별 조건</summary>
        {group.rows.map((row) => (
          <HousingTypeCard
            areaUnit={areaUnit}
            key={row.supplyRowId}
            complexName={group.name}
            row={row}
            onOpenFloorPlan={onOpenFloorPlan}
          />
        ))}
      </details>
    </section>
  )
}

function SupplyTableRow({ areaUnit, row, showOccupancy }: {
  areaUnit: AreaUnit
  row: HousingAnnouncementDetailSupplyRow
  showOccupancy: boolean
}) {
  const deposit = moneyRange([row], 'deposit')
  const monthlyRent = moneyRange([row], 'monthlyRent')
  const hasMoney = deposit !== MISSING_DATA_LABEL || monthlyRent !== MISSING_DATA_LABEL
  const occupancy = showOccupancy && row.occupancyExpectedYearMonth ? `${formatYearMonth(row.occupancyExpectedYearMonth)} 입주` : null
  return (
    <tr>
      <th scope="row">
        <strong>{housingTypeLabel(row)}</strong>
        <span>{[row.supplyTypeLabel, occupancy].filter(Boolean).join(' · ')}</span>
      </th>
      <td>{formatArea(row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea, areaUnit)}</td>
      <td>{row.sharedRecruitment ? <span aria-label="개별 모집수 미확정, 단지 공통 합계에 포함">미확정</span> : formatNullableCount(row.totalSupplyHouseholdCount, '')}</td>
      <td>
        {hasMoney ? (
          <>
            <strong data-emphasis={deposit !== MISSING_DATA_LABEL || undefined}>{deposit}</strong>
            <span>{monthlyRent}</span>
          </>
        ) : <span data-missing="true">{MISSING_DATA_LABEL}</span>}
      </td>
    </tr>
  )
}

function housingTypeLabel(row: HousingAnnouncementDetailSupplyRow) {
  if ((row.corrected || row.sharedRecruitment) && row.sourceHousingTypeName) return row.sourceHousingTypeName
  return row.housingType?.name ?? row.sourceHousingTypeName ?? MISSING_DATA_LABEL
}

/** 접어 둔 주택형 상세: 평면도, 공급면적·입주 예정과 대상별 조건. */
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
      <div role="group" aria-label={`${complexName} ${housingTypeName} 공급 정보`}>
        <DetailFacts appearance="list">
          <DetailFact term="전용면적" value={formatArea(row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea, areaUnit)} />
          <DetailFact term="공급면적" value={formatArea(row.supplyArea === undefined ? row.housingType?.supplyArea ?? null : row.supplyArea, areaUnit)} />
          <DetailFact term="공급 세대수" value={row.sharedRecruitment ? '공통 합계 · 개별 미확정' : formatNullableCount(row.totalSupplyHouseholdCount, '세대')} />
          <DetailFact term="입주 예정" value={formatYearMonth(row.occupancyExpectedYearMonth)} />
        </DetailFacts>
      </div>
      <SupplyTargets targets={row.targets} />
    </article>
  )
}

function SupplyTargets({ targets }: { targets: readonly AnnouncementSupplyTarget[] }) {
  const visibleTargets = targets.filter((target) => (
    [target.target, target.priority, target.applicationCondition].some(hasText)
    || [target.supplyHouseholdCount, target.waitlistCount, target.deposit, target.monthlyRent].some(Number.isFinite)
  ))
  if (visibleTargets.length === 0) return null
  return (
    <ul className={styles.targetList} aria-label="대상별 공급 조건">
      {visibleTargets.map((target) => (
        <li key={target.supplyTargetId}>
          <header className={styles.targetHeading}>
            <strong>{target.target ?? MISSING_DATA_LABEL}</strong>
            {hasText(target.priority) && <span>{target.priority}</span>}
          </header>
          <div role="group" aria-label={`${target.target ?? MISSING_DATA_LABEL} 공급 조건`}>
            <DetailFacts appearance="list">
              <DetailFact term="공급 세대수" value={formatNullableCount(target.supplyHouseholdCount, '세대')} />
              <DetailFact term="모집 예비자 수" value={formatNullableCount(target.waitlistCount, '명')} />
              <DetailFact term="보증금" value={formatHousingMoneyReadable(target.deposit)} emphasis={Number.isFinite(target.deposit)} />
              <DetailFact term="월 임대료" value={formatHousingMoneyReadable(target.monthlyRent)} emphasis={Number.isFinite(target.monthlyRent)} />
              {hasText(target.applicationCondition) && (
                <DetailFact term="신청 조건" value={target.applicationCondition} wide />
              )}
            </DetailFacts>
          </div>
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
        {sourceUrl && <a href={sourceUrl} target="_blank" rel="noreferrer" onClick={(event) => {
          captureProductEvent('announcement_source_clicked', { announcement_id: detail.announcementId,
            detail_visit_id: detailVisitId(event.currentTarget) })
        }}>공고 원문</a>}
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
