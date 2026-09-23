import {
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react'
import {
  DetailCloseButton,
  DetailFact,
  DetailFacts,
  DetailSection,
  DetailTable,
} from './DetailPrimitives'
import { formatHousingMoney } from '../presentation/housingMoney'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { AnnouncementStatusBadge } from './AnnouncementStatusBadge'
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
  readonly agencyCode?: string | null
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
  readonly backButton?: ReactNode
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
  backButton,
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
  const activeHousingTypeId = selectedHousingType?.housingTypeId
  const choiceRefs = useRef(new Map<string, HTMLButtonElement>())
  const tabListRef = useRef<HTMLDivElement>(null)
  const housingTypeIdPrefix = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const validImages = detail.images.filter(isSafeHttpUrl)
  const validOverviewImage = safeHttpUrl(detail.overviewImageUrl)
  const usesOverviewAsCover = validImages.length === 0 && validOverviewImage !== null
  const coverImages = validImages.length > 0
    ? validImages
    : validOverviewImage === null ? [] : [validOverviewImage]
  const agencyCode = detail.agencyCode?.trim().toUpperCase() || null
  const titleId = `complex-detail-title-${detail.complexId}`

  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true })
  }, [detail.complexId])

  useEffect(() => {
    if (activeHousingTypeId !== undefined) {
      revealTab(tabListRef.current, choiceRefs.current.get(activeHousingTypeId))
    }
  }, [detail.complexId, activeHousingTypeId])

  function selectHousingType(housingTypeId: string) {
    setSelection({ complexId: detail.complexId, housingTypeId })
    revealHousingType(housingTypeId)
  }

  function revealHousingType(housingTypeId: string) {
    revealTab(tabListRef.current, choiceRefs.current.get(housingTypeId))
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
    choiceRefs.current.get(target.housingTypeId)?.focus({ preventScroll: true })
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
        {backButton}
        <strong className={styles.headerTitle} title={detail.name}>{detail.name}</strong>
        <DetailCloseButton label="단지 상세 닫기" onClose={onClose} />
      </header>

      <div
        className={styles.scroll}
        role="region"
        aria-label={`${detail.name} 단지 상세 내용`}
        tabIndex={0}
      >
        {coverImages.length > 0 && (
          <ComplexImages
            name={detail.name}
            urls={coverImages}
            overview={usesOverviewAsCover}
          />
        )}

        <section className={styles.identity} aria-labelledby={titleId}>
          <div className={styles.identityHeading}>
            <h2 ref={headingRef} id={titleId} tabIndex={-1}>{detail.name}</h2>
            <p className={styles.identityType}>
              <strong data-agency={agencyCode} title={detail.agencyName}>{agencyCode ?? detail.agencyName}</strong>
              <span aria-hidden="true">·</span>
              <span>{detail.rentalTypeLabel}</span>
            </p>
          </div>
          <p className={styles.address}>{displayAddress(detail)}</p>
        </section>

        <CurrentAnnouncements
          announcements={detail.currentAnnouncements}
          onOpenAnnouncement={onOpenAnnouncement}
        />

        <BasicInformation detail={detail} />

        {selectedHousingType && (
          <>
            <DetailSection title="주택형별 임대조건">
              <div className={styles.comparison}>
                <DetailTable caption="주택형별 임대조건 비교" minWidth={520}>
                  <colgroup>
                    <col style={{ width: '18%' }} />
                    <col style={{ width: '28%' }} />
                    <col style={{ width: '27%' }} />
                    <col style={{ width: '27%' }} />
                  </colgroup>
                  <thead>
                    <tr>
                      <th scope="col">주택형</th>
                      <th scope="col">전용면적<span className={styles.conditionTarget}>공급 대상</span></th>
                      <th scope="col">보증금</th>
                      <th scope="col">월 임대료</th>
                    </tr>
                  </thead>
                  {detail.housingTypes.map((housingType) => {
                    const conditions: readonly (HousingComplexDetailSupplyCondition | null)[] =
                      housingType.currentSupplyConditions.length > 0 ? housingType.currentSupplyConditions : [null]
                    return (
                      <tbody key={housingType.housingTypeId}>
                        {conditions.map((condition, conditionIndex) => (
                          <tr key={conditionIndex}>
                            {conditionIndex === 0 && (
                              <th scope="rowgroup" rowSpan={conditions.length}>
                                {housingTypeName(housingType)}
                              </th>
                            )}
                            <td>
                              {formatArea(housingType.exclusiveArea)}
                              <span className={styles.conditionTarget}>{condition?.target ?? MISSING_DATA_LABEL}</span>
                            </td>
                            <td data-numeric data-emphasis={Number.isFinite(condition?.deposit) || undefined}>
                              {formatHousingMoney(condition?.deposit ?? null)}
                            </td>
                            <td data-numeric data-emphasis={Number.isFinite(condition?.monthlyRent) || undefined}>
                              {formatHousingMoney(condition?.monthlyRent ?? null)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    )
                  })}
                </DetailTable>
              </div>
            </DetailSection>
            <DetailSection title="주택형 정보">
              <div
                ref={tabListRef}
                className={styles.housingTypeTabs}
                role="tablist"
                aria-label="주택형 선택"
              >
                {detail.housingTypes.map((housingType, index) => {
                  const selected = housingType.housingTypeId === selectedHousingType.housingTypeId
                  const id = `${housingTypeIdPrefix}-${housingType.housingTypeId}`
                  return (
                    <button
                      key={housingType.housingTypeId}
                      ref={(node) => setChoiceRef(choiceRefs.current, housingType.housingTypeId, node)}
                      id={`${id}-tab`}
                      className={styles.housingTypeTab}
                      type="button"
                      role="tab"
                      aria-selected={selected}
                      aria-controls={`${id}-panel`}
                      tabIndex={selected ? 0 : -1}
                      onClick={() => selectHousingType(housingType.housingTypeId)}
                      onFocus={() => revealHousingType(housingType.housingTypeId)}
                      onKeyDown={(event) => handleHousingTypeKeyDown(event, index)}
                    >
                      {housingTypeName(housingType)}
                    </button>
                  )
                })}
              </div>
              {detail.housingTypes.map((housingType) => {
                const selected = housingType.housingTypeId === selectedHousingType.housingTypeId
                const id = `${housingTypeIdPrefix}-${housingType.housingTypeId}`
                return (
                  <div
                    key={housingType.housingTypeId}
                    id={`${id}-panel`}
                    className={styles.housingTypePanel}
                    role="tabpanel"
                    aria-labelledby={`${id}-tab`}
                    tabIndex={0}
                    hidden={!selected}
                  >
                    {selected && <HousingTypePanel housingType={housingType} />}
                  </div>
                )
              })}
            </DetailSection>
          </>
        )}

        {validOverviewImage && !usesOverviewAsCover && (
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

function ComplexImages({
  name,
  urls,
  overview,
}: {
  name: string
  urls: readonly string[]
  overview: boolean
}) {
  return (
    <section
      className={styles.imageGallery}
      aria-label={overview ? '단지 조감도' : '단지 사진'}
      data-overview={overview || undefined}
    >
      {urls.map((url, index) => (
        <img
          key={`${url}-${index}`}
          src={url}
          alt={overview
            ? `${name} 단지 조감도`
            : urls.length === 1 ? `${name} 단지 사진` : `${name} 단지 사진 ${index + 1}`}
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
      description="정정 내용을 반영한 모집 건별 최신 공고입니다."
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
  const title = announcement.title ?? MISSING_DATA_LABEL
  const hasCountdown = announcement.applicationStatus !== 'CLOSED'
    && announcement.dDay !== null
    && Number.isInteger(announcement.dDay)
    && announcement.dDay >= 0
  const deadlineTone = isUrgent(announcement) ? 'urgent' : hasCountdown ? 'countdown' : 'neutral'

  return (
    <article
      className={styles.announcementCard}
      data-urgency={isUrgent(announcement) ? 'urgent' : undefined}
    >
      <header className={styles.announcementHeading}>
        <div className={styles.announcementStatusRow}>
          <AnnouncementStatusBadge
            label={applicationStatusLabel(announcement.applicationStatus)}
            tone={applicationStatusTone(announcement.applicationStatus)}
            countdown={null}
          />
          <p className={styles.announcementDeadline} data-tone={deadlineTone}>
            <span className={styles.visuallyHidden}>마감까지 </span>
            <span>{announcementDDay(announcement)}</span>
          </p>
        </div>
        <h4>{title}</h4>
        {announcement.publicationTypeLabel && <small>{announcement.publicationTypeLabel}</small>}
      </header>
      <div className={styles.announcementBody}>
        <DetailFacts columns={2}>
          <DetailFact term="접수 시작" value={displayDate(announcement.applicationStartAt)} />
          <DetailFact term="접수 종료" value={displayDate(announcement.applicationEndAt)} />
          {announcement.actualCompetitionRate !== null && (
            <DetailFact
              term="실제 경쟁률"
              value={`${formatNumber(announcement.actualCompetitionRate)} : 1`}
            />
          )}
          {announcement.targets.length > 0 && (
            <DetailFact term="공고 대상" value={announcement.targets.join(' · ')} wide />
          )}
        </DetailFacts>
        {onOpenAnnouncement && (
          <button
            type="button"
            className={styles.announcementAction}
            data-detail-return-focus={`announcement:${announcement.announcementId}`}
            aria-label={`${title} 상세 보기`}
            onClick={() => onOpenAnnouncement(announcement.announcementId)}
          >
            공고 상세 보기
          </button>
        )}
      </div>
    </article>
  )
}

function BasicInformation({ detail }: { detail: HousingComplexDetailData }) {
  return (
    <DetailSection
      title="단지 기본 정보"
      description="단지의 건물 특성과 규모입니다."
    >
      <DetailFacts columns={2}>
        <DetailFact term="공급기관" value={detail.agencyName} />
        <DetailFact term="임대종류" value={detail.rentalTypeLabel} />
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
      </DetailFacts>
    </DetailSection>
  )
}

function HousingTypePanel({
  housingType,
}: {
  housingType: HousingComplexDetailHousingType
}) {
  const floorPlanImages = floorPlanUrls(housingType)
  const name = housingTypeName(housingType)

  return (
    <>
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
      <DetailFacts columns={2}>
        <DetailFact term="주택형" value={name} />
        <DetailFact term="전용 면적" value={formatArea(housingType.exclusiveArea)} />
        <DetailFact term="공급 면적" value={formatArea(housingType.supplyArea)} />
        <DetailFact term="복층여부" value={duplexLabel(housingType.isDuplex)} />
        <DetailFact
          term="관리비"
          value={formatHousingMoney(housingType.maintenanceFee)}
          emphasis={Number.isFinite(housingType.maintenanceFee)}
        />
      </DetailFacts>

      <h4>현재 공급 조건</h4>
      {housingType.currentSupplyConditions.length === 0 && (
        <p className={styles.empty}>{MISSING_DATA_LABEL}</p>
      )}
      {housingType.currentSupplyConditions.length > 0 && (
        <ul className={styles.supplyList}>
          {housingType.currentSupplyConditions.map((condition, index) => (
            <li key={`${condition.target ?? 'unknown'}-${index}`}>
              <strong>{condition.target ?? MISSING_DATA_LABEL}</strong>
              <SupplyConditionTable housingTypeName={name} condition={condition} />
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function SupplyConditionTable({
  housingTypeName,
  condition,
}: {
  housingTypeName: string
  condition: HousingComplexDetailSupplyCondition
}) {
  const id = useId()
  return (
    <DetailTable caption={`${housingTypeName} ${condition.target ?? MISSING_DATA_LABEL} 현재 공급 조건`}>
      <colgroup>
        <col style={{ width: '22%' }} />
        <col style={{ width: '28%' }} />
        <col style={{ width: '22%' }} />
        <col style={{ width: '28%' }} />
      </colgroup>
      <tbody>
        <tr>
          <th id={`${id}-deposit`} scope="row">임대보증금</th>
          <td headers={`${id}-deposit`} data-numeric data-emphasis={Number.isFinite(condition.deposit) || undefined}>
            {formatHousingMoney(condition.deposit)}
          </td>
          <th id={`${id}-rent`} scope="row">월 임대료</th>
          <td headers={`${id}-rent`} data-numeric data-emphasis={Number.isFinite(condition.monthlyRent) || undefined}>
            {formatHousingMoney(condition.monthlyRent)}
          </td>
        </tr>
        <tr>
          <th id={`${id}-convertible`} scope="row">전환 가능 보증금</th>
          <td
            headers={`${id}-convertible`}
            colSpan={3}
            data-numeric
            data-emphasis={Number.isFinite(condition.convertibleDeposit) || undefined}
          >
            {formatHousingMoney(condition.convertibleDeposit)}
          </td>
        </tr>
      </tbody>
    </DetailTable>
  )
}

function revealTab(tabList: HTMLDivElement | null, tab: HTMLButtonElement | undefined) {
  if (!tabList || !tab) return
  const viewport = tabList.getBoundingClientRect()
  const choice = tab.getBoundingClientRect()
  if (choice.left < viewport.left) {
    tabList.scrollLeft += choice.left - viewport.left
  } else if (choice.right > viewport.right) {
    tabList.scrollLeft += choice.right - viewport.right
  }
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
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(key)) {
    return null
  }
  const offset = key === 'ArrowRight' || key === 'ArrowDown' ? 1 : -1
  const targetIndex = (index + offset + housingTypes.length) % housingTypes.length
  return housingTypes[targetIndex] ?? null
}

function setChoiceRef(
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
  return housingType.name ?? MISSING_DATA_LABEL
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
  return detail.roadAddress ?? detail.regionName ?? MISSING_DATA_LABEL
}

function displayDate(value: string | null) {
  return formattedDate(value) ?? MISSING_DATA_LABEL
}

function formattedDate(value: string | null) {
  if (value === null || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  return value.replaceAll('-', '.')
}

function availability(value: boolean | null) {
  if (value === null) {
    return MISSING_DATA_LABEL
  }
  return value ? '있음' : '없음'
}

function duplexLabel(value: boolean | null) {
  if (value === null) {
    return MISSING_DATA_LABEL
  }
  return value ? '복층' : '해당 없음'
}

function formatNullableCount(value: number | null, suffix: string) {
  if (value === null || !Number.isFinite(value)) {
    return MISSING_DATA_LABEL
  }
  return `${formatNumber(value)}${suffix}`
}

function parkingSummary(parkingCount: number | null, householdCount: number | null) {
  if (parkingCount === null || !Number.isFinite(parkingCount)) {
    return MISSING_DATA_LABEL
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
    return MISSING_DATA_LABEL
  }
  return `${formatNumber(value)}㎡`
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
  return MISSING_DATA_LABEL
}

function applicationStatusTone(status: string | null) {
  if (status === 'BEFORE_APPLICATION') {
    return 'upcoming'
  }
  if (status === 'APPLYING') {
    return 'applying'
  }
  if (status === 'CLOSED') {
    return 'closed'
  }
  return 'unknown'
}

function announcementDDay(announcement: HousingComplexDetailAnnouncement) {
  if (announcement.applicationStatus === 'CLOSED') {
    return '종료'
  }
  if (announcement.dDay === null || !Number.isInteger(announcement.dDay)) {
    return MISSING_DATA_LABEL
  }
  if (announcement.dDay < 0) {
    return MISSING_DATA_LABEL
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
