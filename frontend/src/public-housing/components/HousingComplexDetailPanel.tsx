import {
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useRef,
  useState,
} from 'react'
import styles from './HousingComplexDetailPanel.module.css'

export interface HousingComplexDetailSupplyCondition {
  readonly target: string | null
  readonly deposit: number | null
  readonly monthlyRent: number | null
  readonly convertibleDeposit: number | null
}

export interface HousingComplexDetailHousingType {
  readonly housingTypeId: string
  readonly name: string | null
  readonly exclusiveArea: number | null
  readonly supplyArea: number | null
  readonly floorPlanImageUrl: string | null
  readonly floorPlan3dImageUrl: string | null
  readonly isDuplex: boolean | null
  readonly maintenanceFee: number | null
  readonly currentSupplyConditions: readonly HousingComplexDetailSupplyCondition[]
}

export interface HousingComplexDetailAnnouncement {
  readonly announcementId: string
  readonly title: string | null
  readonly publicationTypeLabel: string | null
  readonly applicationStatus: string | null
  readonly targets: readonly string[]
  readonly applicationStartAt: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
  readonly actualCompetitionRate: number | null
}

export interface HousingComplexDetailData {
  readonly complexId: string
  readonly name: string
  readonly rentalTypeLabel: string
  readonly agencyName: string
  readonly regionName: string | null
  readonly roadAddress: string | null
  readonly completionDate: string | null
  readonly buildingTypeLabel: string
  readonly hasElevator: boolean | null
  readonly heatingTypeLabel: string
  readonly corridorTypeLabel: string
  readonly moveOutCountLastYear: number | null
  readonly totalHouseholdCount: number | null
  readonly totalParkingCount: number | null
  readonly images: readonly string[]
  readonly overviewImageUrl: string | null
  readonly housingTypes: readonly HousingComplexDetailHousingType[]
  readonly currentAnnouncements: readonly HousingComplexDetailAnnouncement[]
}

export interface HousingComplexDetailPanelProps {
  readonly detail: HousingComplexDetailData
  readonly onClose: () => void
  readonly onOpenAnnouncement?: (announcementId: string) => void
}

interface HousingTypeSelection {
  readonly complexId: string
  readonly housingTypeId: string | null
}

export function HousingComplexDetailPanel({
  detail,
  onClose,
  onOpenAnnouncement,
}: HousingComplexDetailPanelProps) {
  const initialHousingTypeId = detail.housingTypes[0]?.housingTypeId ?? null
  const [selection, setSelection] = useState<HousingTypeSelection>({
    complexId: detail.complexId,
    housingTypeId: initialHousingTypeId,
  })
  const selectedHousingTypeId = selection.complexId === detail.complexId
    ? selection.housingTypeId
    : initialHousingTypeId
  const selectedHousingType = detail.housingTypes.find(
    (housingType) => housingType.housingTypeId === selectedHousingTypeId,
  ) ?? detail.housingTypes[0]
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())
  const headingRef = useRef<HTMLHeadingElement>(null)
  const validImages = detail.images.filter(isSafeHttpUrl)
  const validOverviewImage = safeHttpUrl(detail.overviewImageUrl)
  const titleId = `complex-detail-title-${detail.complexId}`

  useEffect(() => {
    headingRef.current?.focus()
  }, [detail.complexId])

  function selectHousingType(housingTypeId: string) {
    setSelection({ complexId: detail.complexId, housingTypeId })
  }

  function handleHousingTypeKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) {
    const target = keyboardTarget(detail.housingTypes, index, event.key)
    if (target === null) {
      return
    }
    event.preventDefault()
    selectHousingType(target.housingTypeId)
    tabRefs.current.get(target.housingTypeId)?.focus()
  }

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
      aria-label={`${detail.name} 단지 상세 정보`}
      onKeyDown={handlePanelKeyDown}
    >
      <header className={styles.header}>
        <div>
          <span>단지 상세 정보</span>
          <strong>{detail.name}</strong>
        </div>
        <button
          type="button"
          className={styles.closeButton}
          aria-label="단지 상세 닫기"
          onClick={onClose}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>

      <div
        className={styles.scroll}
        role="region"
        aria-label={`${detail.name} 단지 상세 내용`}
        tabIndex={0}
      >
        {validImages.length > 0 && (
          <ComplexImages name={detail.name} urls={validImages} />
        )}

        <section className={styles.identity} aria-labelledby={titleId}>
          <p className={styles.identityType}>
            <strong>{detail.agencyName}</strong>
            <span aria-hidden="true">·</span>
            {detail.rentalTypeLabel}
          </p>
          <h2 ref={headingRef} id={titleId} tabIndex={-1}>{detail.name}</h2>
          <p className={styles.address}>{displayAddress(detail)}</p>
        </section>

        <CurrentAnnouncements
          announcements={detail.currentAnnouncements}
          onOpenAnnouncement={onOpenAnnouncement}
        />

        <BasicInformation detail={detail} />

        {selectedHousingType && (
          <DetailSection
            title="주택형 정보"
            description="주택형별 면적과 현재 공급 조건을 확인하세요."
          >
            <div className={styles.housingTypeTabs} role="tablist" aria-label="주택형 선택">
              {detail.housingTypes.map((housingType, index) => (
                <button
                  key={housingType.housingTypeId}
                  ref={(node) => setTabRef(tabRefs.current, housingType.housingTypeId, node)}
                  type="button"
                  className={styles.housingTypeTab}
                  role="tab"
                  aria-selected={housingType.housingTypeId === selectedHousingType.housingTypeId}
                  aria-controls={`housing-type-panel-${detail.complexId}`}
                  tabIndex={housingType.housingTypeId === selectedHousingType.housingTypeId ? 0 : -1}
                  onClick={() => selectHousingType(housingType.housingTypeId)}
                  onKeyDown={(event) => handleHousingTypeKeyDown(event, index)}
                >
                  {housingTypeName(housingType)}
                </button>
              ))}
            </div>
            <HousingTypePanel
              complexId={detail.complexId}
              housingType={selectedHousingType}
            />
          </DetailSection>
        )}

        {validOverviewImage && (
          <DetailSection title="단지 조감도">
            <img
              className={styles.overviewImage}
              src={validOverviewImage}
              alt={`${detail.name} 단지 조감도`}
              loading="lazy"
            />
          </DetailSection>
        )}
      </div>
    </aside>
  )
}

function ComplexImages({ name, urls }: { name: string; urls: readonly string[] }) {
  return (
    <section className={styles.imageGallery} aria-label="단지 사진">
      {urls.map((url, index) => (
        <img
          key={`${url}-${index}`}
          src={url}
          alt={urls.length === 1 ? `${name} 단지 사진` : `${name} 단지 사진 ${index + 1}`}
          loading={index === 0 ? 'eager' : 'lazy'}
        />
      ))}
    </section>
  )
}

function CurrentAnnouncements({
  announcements,
  onOpenAnnouncement,
}: {
  announcements: readonly HousingComplexDetailAnnouncement[]
  onOpenAnnouncement?: (announcementId: string) => void
}) {
  return (
    <DetailSection
      title="현재 모집 공고"
      description="이 단지와 연결된 현재 공고입니다."
    >
      {announcements.length === 0 && (
        <p className={styles.empty}>현재 연결된 모집 공고가 없습니다.</p>
      )}
      {announcements.length > 0 && (
        <ul className={styles.announcementList}>
          {announcements.map((announcement) => (
            <li key={announcement.announcementId}>
              <AnnouncementCard
                announcement={announcement}
                onOpenAnnouncement={onOpenAnnouncement}
              />
            </li>
          ))}
        </ul>
      )}
    </DetailSection>
  )
}

function AnnouncementCard({
  announcement,
  onOpenAnnouncement,
}: {
  announcement: HousingComplexDetailAnnouncement
  onOpenAnnouncement?: (announcementId: string) => void
}) {
  const title = announcement.title ?? '공고명 정보 확인 중'

  return (
    <article
      className={styles.announcementCard}
      data-urgency={isUrgent(announcement) ? 'urgent' : undefined}
    >
      <header className={styles.announcementHeading}>
        <span>{applicationStatusLabel(announcement.applicationStatus)}</span>
        <h4>{title}</h4>
        {announcement.publicationTypeLabel && <small>{announcement.publicationTypeLabel}</small>}
      </header>
      <dl className={styles.announcementFacts}>
        <DetailFact term="접수 기간" value={dateRange(
          announcement.applicationStartAt,
          announcement.applicationEndAt,
        )} />
        <DetailFact term="마감까지" value={announcementDDay(announcement)} />
        {announcement.targets.length > 0 && (
          <DetailFact term="공고 대상" value={announcement.targets.join(' · ')} wide />
        )}
        {announcement.actualCompetitionRate !== null && (
          <DetailFact
            term="실제 경쟁률"
            value={`${formatNumber(announcement.actualCompetitionRate)} : 1`}
          />
        )}
      </dl>
      {onOpenAnnouncement && (
        <button
          type="button"
          className={styles.announcementAction}
          aria-label={`${title} 공고 상세 보기`}
          onClick={() => onOpenAnnouncement(announcement.announcementId)}
        >
          공고 상세 보기
        </button>
      )}
    </article>
  )
}

function BasicInformation({ detail }: { detail: HousingComplexDetailData }) {
  return (
    <DetailSection
      title="단지 기본 정보"
      description="API에서 확인된 단지의 건물 특성과 규모입니다."
    >
      <dl className={styles.facts}>
        <DetailFact term="단지명" value={detail.name} wide />
        <DetailFact term="공급기관" value={detail.agencyName} />
        <DetailFact term="임대종류" value={detail.rentalTypeLabel} />
        <DetailFact term="상세주소" value={displayAddress(detail)} wide />
        <DetailFact term="준공일자" value={displayDate(detail.completionDate)} />
        <DetailFact term="건물형태" value={detail.buildingTypeLabel} />
        <DetailFact term="엘리베이터" value={availability(detail.hasElevator)} />
        <DetailFact term="난방종류" value={detail.heatingTypeLabel} />
        <DetailFact term="복도유형" value={detail.corridorTypeLabel} />
        <DetailFact
          term="1년 퇴거 세대수"
          value={formatNullableCount(detail.moveOutCountLastYear, '세대')}
        />
        <DetailFact
          term="총세대수"
          value={formatNullableCount(detail.totalHouseholdCount, '세대')}
        />
        <DetailFact
          term="총주차대수(세대당)"
          value={parkingSummary(detail.totalParkingCount, detail.totalHouseholdCount)}
        />
      </dl>
    </DetailSection>
  )
}

function HousingTypePanel({
  complexId,
  housingType,
}: {
  complexId: string
  housingType: HousingComplexDetailHousingType
}) {
  const floorPlanImages = floorPlanUrls(housingType)
  const name = housingTypeName(housingType)

  return (
    <div
      id={`housing-type-panel-${complexId}`}
      className={styles.housingTypePanel}
      role="tabpanel"
      aria-label={`${name} 주택형 상세`}
    >
      {floorPlanImages.length > 0 && (
        <div className={styles.floorPlans}>
          {floorPlanImages.map(({ label, url }) => (
            <figure key={label}>
              <img src={url} alt={`${name} ${label}`} loading="lazy" />
              <figcaption>{label}</figcaption>
            </figure>
          ))}
        </div>
      )}

      <h4>선택 주택형 상세</h4>
      <dl className={styles.facts}>
        <DetailFact term="주택형" value={name} />
        <DetailFact term="전용 면적" value={formatArea(housingType.exclusiveArea)} />
        <DetailFact term="공급 면적" value={formatArea(housingType.supplyArea)} />
        <DetailFact term="복층여부" value={duplexLabel(housingType.isDuplex)} />
        <DetailFact term="관리비" value={formatMoney(housingType.maintenanceFee)} />
      </dl>

      <h4>현재 공급 조건</h4>
      {housingType.currentSupplyConditions.length === 0 && (
        <p className={styles.empty}>현재 연결된 공급 조건이 없습니다.</p>
      )}
      {housingType.currentSupplyConditions.length > 0 && (
        <ul className={styles.supplyList}>
          {housingType.currentSupplyConditions.map((condition, index) => (
            <li key={`${condition.target ?? 'unknown'}-${index}`}>
              <strong>{condition.target ?? '대상 정보 확인 중'}</strong>
              <dl className={styles.supplyFacts}>
                <DetailFact term="임대보증금" value={formatMoney(condition.deposit)} />
                <DetailFact term="월 임대료" value={formatMoney(condition.monthlyRent)} />
                <DetailFact
                  term="전환 가능 보증금"
                  value={formatMoney(condition.convertibleDeposit)}
                />
              </dl>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function DetailSection({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: ReactNode
}) {
  return (
    <section className={styles.section}>
      <header className={styles.sectionHeading}>
        <h3>{title}</h3>
        {description && <p>{description}</p>}
      </header>
      {children}
    </section>
  )
}

function DetailFact({
  term,
  value,
  wide = false,
}: {
  term: string
  value: string
  wide?: boolean
}) {
  return (
    <div className={wide ? styles.wideFact : undefined}>
      <dt>{term}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function keyboardTarget(
  housingTypes: readonly HousingComplexDetailHousingType[],
  index: number,
  key: string,
) {
  if (key === 'Home') {
    return housingTypes[0] ?? null
  }
  if (key === 'End') {
    return housingTypes.at(-1) ?? null
  }
  if (key !== 'ArrowLeft' && key !== 'ArrowRight') {
    return null
  }
  const offset = key === 'ArrowRight' ? 1 : -1
  const targetIndex = (index + offset + housingTypes.length) % housingTypes.length
  return housingTypes[targetIndex] ?? null
}

function setTabRef(
  refs: Map<string, HTMLButtonElement>,
  housingTypeId: string,
  node: HTMLButtonElement | null,
) {
  if (node === null) {
    refs.delete(housingTypeId)
    return
  }
  refs.set(housingTypeId, node)
}

function floorPlanUrls(housingType: HousingComplexDetailHousingType) {
  return [
    { label: '평면도', url: safeHttpUrl(housingType.floorPlanImageUrl) },
    { label: '3D 평면도', url: safeHttpUrl(housingType.floorPlan3dImageUrl) },
  ].filter((image): image is { label: string; url: string } => image.url !== null)
}

function housingTypeName(housingType: HousingComplexDetailHousingType) {
  return housingType.name ?? '주택형 정보 확인 중'
}

function isSafeHttpUrl(value: string) {
  return safeHttpUrl(value) !== null
}

function safeHttpUrl(value: string | null) {
  if (value === null) {
    return null
  }
  try {
    const url = new URL(value)
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      return url.href
    }
  } catch {
    return null
  }
  return null
}

function displayAddress(detail: HousingComplexDetailData) {
  return detail.roadAddress ?? detail.regionName ?? '정보 확인 중'
}

function displayDate(value: string | null) {
  return formattedDate(value) ?? '정보 확인 중'
}

function formattedDate(value: string | null) {
  if (value === null || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  return value.replaceAll('-', '.')
}

function dateRange(start: string | null, end: string | null) {
  const values = [formattedDate(start), formattedDate(end)].filter(isString)
  if (values.length === 0) {
    return '정보 확인 중'
  }
  if (values.length === 1 || values[0] === values[1]) {
    return values[0]
  }
  return `${values[0]} – ${values[1]}`
}

function isString(value: string | null): value is string {
  return value !== null
}

function availability(value: boolean | null) {
  if (value === null) {
    return '정보 확인 중'
  }
  return value ? '있음' : '없음'
}

function duplexLabel(value: boolean | null) {
  if (value === null) {
    return '정보 확인 중'
  }
  return value ? '복층' : '해당 없음'
}

function formatNullableCount(value: number | null, suffix: string) {
  if (value === null || !Number.isFinite(value)) {
    return '정보 확인 중'
  }
  return `${formatNumber(value)}${suffix}`
}

function parkingSummary(parkingCount: number | null, householdCount: number | null) {
  if (parkingCount === null || !Number.isFinite(parkingCount)) {
    return '정보 확인 중'
  }
  const total = `${formatNumber(parkingCount)}대`
  if (householdCount === null || householdCount <= 0 || !Number.isFinite(householdCount)) {
    return total
  }
  const perHousehold = parkingCount / householdCount
  return `${total} (세대당 ${perHousehold.toLocaleString('ko-KR', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })}대)`
}

function formatArea(value: number | null) {
  if (value === null || !Number.isFinite(value)) {
    return '정보 확인 중'
  }
  return `${formatNumber(value)}㎡`
}

function formatMoney(value: number | null) {
  if (value === null || !Number.isFinite(value)) {
    return '정보 확인 중'
  }
  const amount = Math.max(0, Math.round(value))
  if (amount === 0) {
    return '0원'
  }
  if (amount < 10_000) {
    return `${formatNumber(amount)}원`
  }
  const manWon = amount / 10_000
  return `${manWon.toLocaleString('ko-KR', {
    maximumFractionDigits: Number.isInteger(manWon) ? 0 : 1,
  })}만 원`
}

function formatNumber(value: number) {
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

function applicationStatusLabel(status: string | null) {
  if (status === 'BEFORE_APPLICATION') {
    return '모집예정'
  }
  if (status === 'APPLYING') {
    return '접수중'
  }
  if (status === 'CLOSED') {
    return '접수마감'
  }
  return '정보 확인 중'
}

function announcementDDay(announcement: HousingComplexDetailAnnouncement) {
  if (announcement.applicationStatus === 'CLOSED') {
    return '종료'
  }
  if (announcement.dDay === null || !Number.isInteger(announcement.dDay)) {
    return '정보 확인 중'
  }
  if (announcement.dDay < 0) {
    return '정보 확인 중'
  }
  return `D-${announcement.dDay}`
}

function isUrgent(announcement: HousingComplexDetailAnnouncement) {
  if (announcement.applicationStatus !== 'APPLYING' || announcement.dDay === null) {
    return false
  }
  return Number.isInteger(announcement.dDay)
    && announcement.dDay >= 0
    && announcement.dDay <= 3
}
