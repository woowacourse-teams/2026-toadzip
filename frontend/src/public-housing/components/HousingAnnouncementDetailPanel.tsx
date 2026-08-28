import {
  type KeyboardEvent,
  type RefObject,
  type ReactNode,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from 'react'
import type {
  AnnouncementHousingType,
  AnnouncementSupplyComplex,
  AnnouncementSupplyTarget,
} from '../model/publicHousing.ts'
import {
  groupAnnouncementSupplyRows,
  type HousingAnnouncementSupplyComplexGroup,
} from '../presentation/announcementDetailPresentation.ts'
import styles from './HousingAnnouncementDetailPanel.module.css'

export interface HousingAnnouncementDetailReceptionPlace {
  readonly name: string | null
  readonly methodLabel: string
  readonly address: string | null
  readonly phoneNumber: string | null
  readonly url: string | null
}

export interface HousingAnnouncementDetailSchedule {
  readonly scheduleId: string
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
  readonly attachments: readonly HousingAnnouncementDetailAttachment[]
  readonly supplyRows: readonly HousingAnnouncementDetailSupplyRow[]
}

export interface HousingAnnouncementDetailPanelProps {
  readonly detail: HousingAnnouncementDetailData
  readonly onClose: () => void
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

export function HousingAnnouncementDetailPanel({
  detail,
  onClose,
  onOpenComplex,
}: HousingAnnouncementDetailPanelProps) {
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
  const selectedGroupKey = selection.announcementId === detail.announcementId
    ? selection.groupKey
    : firstGroupKey
  const selectedGroup = groups.find((group) => group.key === selectedGroupKey)
    ?? groups[0]
  const title = detail.title ?? '공고명 정보 확인 중'
  const titleId = `announcement-detail-title-${detail.announcementId}`
  const headingRef = useRef<HTMLHeadingElement>(null)
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())

  useEffect(() => {
    headingRef.current?.focus()
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
        detail={detail}
        title={title}
        titleId={titleId}
        headingRef={headingRef}
        onClose={onClose}
      />

      <div
        className={styles.scroll}
        role="region"
        aria-label={`${title} 상세 내용`}
        tabIndex={0}
      >
        <NoticeIntro detail={detail} groups={groups} />
        <CoreInformation detail={detail} />
        <ReasonNotice detail={detail} />
        <AudienceSection targets={detail.targets} />
        <ScheduleSection detail={detail} />
        <ReceptionPlaces places={detail.receptionPlaces} />
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
        <AttachmentList attachments={detail.attachments} />
      </div>

      <DocumentActions detail={detail} />
      {floorPlan && (
        <FloorPlanDialog selection={floorPlan} onClose={() => setFloorPlan(null)} />
      )}
    </aside>
  )
}

function StickyHeader({
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
  onClose: () => void
}) {
  return (
    <header className={styles.header}>
      <div className={styles.headerMain}>
        <div className={styles.headerContext}>
          <span>{detail.rentalTypeLabel}</span>
          <span data-status={statusTone(detail.applicationStatus)}>
            {detail.applicationStatusLabel}
          </span>
          {detail.publicationTypeLabel !== '원공고' && (
            <span data-publication="changed">{detail.publicationTypeLabel}</span>
          )}
        </div>
        <h2 ref={headingRef} id={titleId} tabIndex={-1} title={title}>{title}</h2>
      </div>
      <div className={styles.headerActions}>
        <span className={styles.headerDeadline} aria-label={deadlineAccessibleLabel(detail)}>
          {deadlineLabel(detail)}
        </span>
        <button
          type="button"
          className={styles.closeButton}
          aria-label="공고 상세 닫기"
          onClick={onClose}
        >
          <span aria-hidden="true">×</span>
        </button>
      </div>
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
  return (
    <section className={styles.intro} aria-label="공고 요약">
      <p>
        <strong>{agencyLabel(detail)}</strong>
        <span aria-hidden="true">·</span>
        <b>{regionLabel(detail.regionNames)}</b>
      </p>
      {firstGroup && (
        <p>
          <span>{firstGroup.name}</span>
          {remainingComplexes > 0 && <em>외 {remainingComplexes}곳</em>}
        </p>
      )}
      <small>{detail.recruitmentTypeLabel}</small>
    </section>
  )
}

function CoreInformation({ detail }: { detail: HousingAnnouncementDetailData }) {
  return (
    <DetailSection title="공고 핵심 정보">
      <div className={styles.deadline}>
        <div>
          <span>접수 마감</span>
          <time dateTime={detail.applicationEndAt ?? undefined}>
            {formatDate(detail.applicationEndAt)}
          </time>
        </div>
        <strong aria-label={deadlineAccessibleLabel(detail)}>
          {deadlineLabel(detail)}
        </strong>
      </div>
      <dl className={styles.coreFacts}>
        <DetailFact
          term="접수기간"
          value={formatDateRange(detail.applicationStartAt, detail.applicationEndAt)}
        />
        <DetailFact
          term="공급 규모"
          value={`${formatNullableCount(detail.supplyComplexCount, '개 단지')} · ${formatNullableCount(detail.supplyHouseholdCount, '세대')}`}
        />
        <DetailFact term="지역" value={regionLabel(detail.regionNames)} />
        <DetailFact term="공사" value={agencyLabel(detail)} />
      </dl>
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

function AudienceSection({ targets }: { targets: readonly string[] }) {
  return (
    <DetailSection
      title="신청 대상"
      description="세부 소득·자산 기준과 최종 신청자격은 공고문에서 확인해 주세요."
    >
      {targets.length === 0 && (
        <EmptyState>신청 대상 정보 확인 중</EmptyState>
      )}
      {targets.length > 0 && (
        <div className={styles.audiences}>
          {targets.map((target, index) => (
            <span key={`${target}-${index}`}>{target}</span>
          ))}
        </div>
      )}
    </DetailSection>
  )
}

function ScheduleSection({ detail }: { detail: HousingAnnouncementDetailData }) {
  const hasApplicationSchedule = detail.schedules.some(
    (schedule) => schedule.type === 'APPLICATION',
  )
  const hasWinnerSchedule = detail.schedules.some(
    (schedule) => schedule.type === 'WINNER_ANNOUNCEMENT',
  )
  const hasApplicationFallback = !hasApplicationSchedule && (
    detail.applicationStartAt !== null || detail.applicationEndAt !== null
  )
  const hasWinnerDate = detail.winnerAnnouncementAt !== null && !hasWinnerSchedule
  const hasSchedule = hasApplicationFallback
    || detail.schedules.length > 0
    || hasWinnerDate

  return (
    <DetailSection title="접수 일정">
      {!hasSchedule && <EmptyState>상세 일정 정보 확인 중</EmptyState>}
      {hasSchedule && (
        <ol className={styles.schedule}>
          {hasApplicationFallback && (
            <ScheduleItem
              label="접수 기간"
              startAt={detail.applicationStartAt}
              endAt={detail.applicationEndAt}
              current={detail.applicationStatus === 'APPLYING'}
            />
          )}
          {detail.schedules.map((schedule) => (
            <ScheduleItem
              key={schedule.scheduleId}
              label={schedule.name ?? schedule.typeLabel}
              startAt={schedule.startAt}
              endAt={schedule.endAt}
            />
          ))}
          {hasWinnerDate && (
            <ScheduleItem
              label="당첨자 발표"
              startAt={detail.winnerAnnouncementAt}
              endAt={null}
            />
          )}
        </ol>
      )}
    </DetailSection>
  )
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
    <li data-current={current || undefined} aria-current={current ? 'step' : undefined}>
      <strong>{label}</strong>
      <time dateTime={startAt ?? undefined}>{formatDateTimeRange(startAt, endAt)}</time>
      {current && <span>현재 단계</span>}
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
          const url = safeHttpUrl(place.url)
          return (
            <li key={`${place.name ?? 'place'}-${index}`}>
              <div>
                <strong>{place.name ?? '접수처 정보 확인 중'}</strong>
                <span>{place.methodLabel}</span>
              </div>
              {hasText(place.address) && <p>{place.address}</p>}
              {hasText(place.phoneNumber) && <p>{place.phoneNumber}</p>}
              {url && <ExternalLink href={url}>접수처 열기</ExternalLink>}
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
      description="주소와 주택형별 면적·임대조건 범위를 한눈에 비교합니다."
      aside={`${formatNullableCount(supplyComplexCount, '개 단지')} · ${formatNullableCount(supplyHouseholdCount, '세대')}`}
    >
      {groups.length === 0 && <EmptyState>연결된 단지 정보 확인 중</EmptyState>}
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
  const imageUrl = safeHttpUrl(group.overviewImageUrl)

  function openComplex() {
    if (!group.complexId || !onOpenComplex) {
      return
    }
    onOpenComplex(group.complexId)
  }

  return (
    <article className={styles.complexCard} aria-label={`${group.name} 단지 비교`}>
      <div className={styles.complexImage}>
        {imageUrl && <img src={imageUrl} alt={`${group.name} 단지 조감도`} loading="lazy" />}
        {!imageUrl && <span>조감도 정보 확인 중</span>}
      </div>
      <div className={styles.complexBody}>
        <div className={styles.complexHeading}>
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
        </div>
        <p>{group.address ?? '상세주소 정보 확인 중'}</p>
        <div className={styles.complexCounts}>
          <span>총 <b>{formatNullableCount(group.totalHouseholdCount, '세대')}</b></span>
          <span>공급 <b>{formatNullableCount(group.supplyHouseholdCount, '세대')}</b></span>
        </div>
      </div>
      <dl className={styles.complexRanges}>
        <DetailFact term="전용면적" value={areaRange(group.rows)} />
        <DetailFact term="보증금" value={moneyRange(group.rows, 'deposit')} />
        <DetailFact term="월 임대료" value={moneyRange(group.rows, 'monthlyRent', true)} />
      </dl>
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
      description="단지를 고른 뒤 공급 구분·면적·공급량·비용을 비교하세요."
    >
      {groups.length > 1 && (
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
              <b>{formatNullableCount(group.supplyHouseholdCount, '세대')}</b>
            </button>
          ))}
        </div>
      )}
      <div
        className={styles.housingTypes}
        id={panelId}
        role={groups.length > 1 ? 'tabpanel' : 'region'}
        aria-labelledby={groups.length > 1
          ? `${idPrefix}-complex-tab-${selectedIndex}`
          : undefined}
        aria-label={groups.length === 1 ? `${selectedGroup.name} 주택형 비교` : undefined}
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
  const housingTypeName = row.housingType?.name
    ?? row.sourceHousingTypeName
    ?? '주택형 정보 확인 중'
  const twoDimensionalUrl = safeHttpUrl(row.housingType?.floorPlanImageUrl ?? null)
  const threeDimensionalUrl = safeHttpUrl(row.housingType?.floorPlan3dImageUrl ?? null)
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
        {!hasFloorPlan && <small>평면도 정보 확인 중</small>}
      </div>
      <dl className={styles.housingTypeMetrics}>
        <DetailFact term="공급 구분" value={row.supplyTypeLabel} />
        <DetailFact term="전용면적" value={formatArea(row.housingType?.exclusiveArea ?? null)} />
        <DetailFact term="공급 세대수" value={formatNullableCount(row.totalSupplyHouseholdCount, '세대')} />
        <DetailFact term="입주 예정" value={formatYearMonth(row.occupancyExpectedYearMonth)} />
      </dl>
      <SupplyTargets targets={row.targets} />
    </article>
  )
}

function SupplyTargets({ targets }: { targets: readonly AnnouncementSupplyTarget[] }) {
  if (targets.length === 0) {
    return <p className={styles.targetEmpty}>대상별 임대조건 정보 확인 중</p>
  }
  return (
    <ul className={styles.targetList} aria-label="대상별 공급 조건">
      {targets.map((target) => (
        <li key={target.supplyTargetId}>
          <div>
            <strong>{target.target ?? '공급 대상 정보 확인 중'}</strong>
            {hasText(target.priority) && <span>{target.priority}</span>}
          </div>
          <dl>
            <DetailFact term="공급 세대수" value={formatNullableCount(target.supplyHouseholdCount, '세대')} />
            <DetailFact term="모집 예비자 수" value={formatNullableCount(target.waitlistCount, '명')} />
            <DetailFact term="보증금" value={formatMoney(target.deposit)} />
            <DetailFact term="월 임대료" value={formatMoney(target.monthlyRent, true)} />
          </dl>
          {hasText(target.applicationCondition) && <p>{target.applicationCondition}</p>}
        </li>
      ))}
    </ul>
  )
}

function AttachmentList({
  attachments,
}: {
  attachments: readonly HousingAnnouncementDetailAttachment[]
}) {
  if (attachments.length <= 1) {
    return null
  }
  return (
    <DetailSection title="첨부파일">
      <ul className={styles.attachmentList}>
        {attachments.map((attachment) => {
          const url = safeHttpUrl(attachment.fileUrl)
          const name = attachment.fileName ?? '파일명 정보 확인 중'
          return (
            <li key={attachment.attachmentId}>
              <span>{attachment.fileTypeLabel}</span>
              <strong>{name}</strong>
              {url && <ExternalLink href={url}>열기</ExternalLink>}
              {!url && <small>링크 확인 중</small>}
            </li>
          )
        })}
      </ul>
    </DetailSection>
  )
}

function DocumentActions({ detail }: { detail: HousingAnnouncementDetailData }) {
  const sourceUrl = safeHttpUrl(detail.documentLinkUrl)
  const primaryAttachment = findPrimaryNoticeAttachment(detail.attachments)
  const attachmentUrl = safeHttpUrl(primaryAttachment?.fileUrl ?? null)
  const attachmentName = primaryAttachment?.fileName ?? '공고문 파일 정보 확인 중'
  const linkStatus = [
    attachmentUrl ? '첨부파일 연결됨' : '첨부파일 링크 확인 중',
    sourceUrl ? '원문 연결됨' : '원문 링크 확인 중',
  ].join(' · ')

  return (
    <footer className={styles.documents}>
      <div>
        <span aria-hidden="true">▤</span>
        <p>
          <strong>공고문</strong>
          <small title={attachmentName}>{attachmentName}</small>
          <em>{linkStatus}</em>
        </p>
      </div>
      <nav aria-label="공고문 바로가기">
        {attachmentUrl && <ExternalLink href={attachmentUrl}>첨부파일</ExternalLink>}
        {!attachmentUrl && <DisabledLink>첨부파일</DisabledLink>}
        {sourceUrl && <ExternalLink href={sourceUrl}>공고 원문</ExternalLink>}
        {!sourceUrl && <DisabledLink>공고 원문</DisabledLink>}
      </nav>
    </footer>
  )
}

function findPrimaryNoticeAttachment(
  attachments: readonly HousingAnnouncementDetailAttachment[],
) {
  const noticeAttachments = attachments.filter((attachment) => (
    isNoticeAttachmentLabel(attachment.fileTypeLabel)
  ))
  return noticeAttachments.find(
    (attachment) => safeHttpUrl(attachment.fileUrl) !== null,
  ) ?? noticeAttachments[0] ?? attachments.find(
    (attachment) => safeHttpUrl(attachment.fileUrl) !== null,
  ) ?? attachments[0]
}

function isNoticeAttachmentLabel(label: string) {
  return label === '공고문'
    || label === '정정공고문'
    || label === '취소공고문'
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
          <button type="button" aria-label="평면도 닫기" onClick={onClose}>×</button>
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
        {!selection.threeDimensionalUrl && <p className={styles.floorPlanStatus}>3D 평면도 정보 없음</p>}
      </section>
    </div>
  )
}

function DetailSection({
  title,
  description,
  aside,
  children,
}: {
  title: string
  description?: string
  aside?: string
  children: ReactNode
}) {
  return (
    <section className={styles.section}>
      <div className={styles.sectionHeading}>
        <div>
          <h3>{title}</h3>
          {description && <p>{description}</p>}
        </div>
        {aside && <span>{aside}</span>}
      </div>
      {children}
    </section>
  )
}

function DetailFact({ term, value }: { term: string; value: string }) {
  return (
    <div>
      <dt>{term}</dt>
      <dd>{value}</dd>
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
  if (detail.applicationStatus === 'CANCELLED') {
    return '공고 취소'
  }
  if (detail.applicationStatus === 'CLOSED') {
    return '접수 마감'
  }
  if (detail.dDay === null) {
    return 'D-day 확인 중'
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
  if (detail.applicationStatus === 'CANCELLED') {
    return '공고 취소'
  }
  if (detail.applicationStatus === 'CLOSED' || (detail.dDay !== null && detail.dDay < 0)) {
    return '접수 마감'
  }
  if (detail.dDay === null) {
    return '접수 마감일 정보 확인 중'
  }
  if (detail.dDay === 0) {
    return '접수 마감일'
  }
  return `접수 마감까지 ${detail.dDay}일`
}

function statusTone(value: string | null) {
  if (value === 'APPLYING') {
    return 'open'
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
  return detail.agencyCode ?? detail.agencyName ?? '공급기관 정보 확인 중'
}

function regionLabel(regions: readonly string[]) {
  return regions.length > 0 ? regions.join(' · ') : '지역 정보 확인 중'
}

function formatDate(value: string | null) {
  if (!value) {
    return '정보 확인 중'
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (!match) {
    return '정보 확인 중'
  }
  return `${match[1]}.${match[2]}.${match[3]}`
}

function formatDateTime(value: string | null) {
  const date = formatDate(value)
  if (date === '정보 확인 중' || !value) {
    return date
  }
  const time = /T(\d{2}):(\d{2})/.exec(value)
  if (!time || (time[1] === '00' && time[2] === '00')) {
    return date
  }
  return `${date} ${time[1]}:${time[2]}`
}

function formatDateRange(start: string | null, end: string | null) {
  if (start === null && end === null) {
    return '정보 확인 중'
  }
  if (start === end) {
    return formatDate(start)
  }
  return `${formatDate(start)} – ${formatDate(end)}`
}

function formatDateTimeRange(start: string | null, end: string | null) {
  if (start === null && end === null) {
    return '정보 확인 중'
  }
  if (start === end || end === null) {
    return formatDateTime(start)
  }
  return `${formatDateTime(start)} – ${formatDateTime(end)}`
}

function formatYearMonth(value: string | null) {
  if (!value) {
    return '정보 확인 중'
  }
  const match = /^(\d{4})-?(\d{2})/.exec(value)
  if (!match) {
    return '정보 확인 중'
  }
  return `${match[1]}.${match[2]}`
}

function formatNullableCount(value: number | null, unit: string) {
  if (value === null) {
    return '정보 확인 중'
  }
  return `${value.toLocaleString('ko-KR')}${unit}`
}

function formatArea(value: number | null) {
  if (value === null) {
    return '정보 확인 중'
  }
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}㎡`
}

function formatMoney(value: number | null, monthly = false) {
  if (value === null) {
    return '정보 확인 중'
  }
  const prefix = monthly ? '월 ' : ''
  return `${prefix}${value.toLocaleString('ko-KR')}원`
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
  monthly = false,
) {
  const values = rows.flatMap((row) => row.targets)
    .map((target) => target[key])
    .filter((value): value is number => value !== null)
  return numericRange(values, (value) => formatMoney(value, monthly))
}

function numericRange(
  values: readonly number[],
  format: (value: number) => string,
) {
  if (values.length === 0) {
    return '정보 확인 중'
  }
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  if (minimum === maximum) {
    return format(minimum)
  }
  return `${format(minimum)} – ${format(maximum)}`
}

function safeHttpUrl(value: string | null) {
  if (!value) {
    return null
  }
  try {
    const url = new URL(value)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return null
    }
    return url.toString()
  } catch {
    return null
  }
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
