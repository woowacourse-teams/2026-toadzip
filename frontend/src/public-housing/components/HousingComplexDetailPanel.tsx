import { captureProductEvent } from '../../analytics/productAnalytics'
import { detailVisitId, useDetailScrollAnalytics } from './detailAnalytics'
import { toHttpUrl } from '../presentation/httpUrl.ts'
import {
  Fragment,
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
} from './DetailPrimitives'
import { formatHousingMoneyReadable } from '../presentation/housingMoney'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { formatHousingArea as formatArea, type AreaUnit } from '../presentation/housingArea'
import { useAreaUnit } from '../presentation/useAreaUnit'
import { AreaUnitToggle } from './AreaUnitToggle'
import { AnnouncementStatusBadge } from './AnnouncementStatusBadge'
import styles from './HousingComplexDetailPanel.module.css'
import { NotificationInterestButton } from '../interest/NotificationInterest'
import { StreetViewEntry } from '../../street-view/StreetViewEntry'
import type { StreetViewController } from '../../street-view/useStreetView'
import type { ComplexBasicRentalCondition } from '../model/publicHousing'
import { formatRentalConditionCollectedDate } from '../presentation/complexDetailPresentation'

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
  readonly basicRentalCondition: ComplexBasicRentalCondition | null
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
  readonly streetView?: StreetViewController
  readonly onEscape?: () => void
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
  streetView,
  onEscape,
}: HousingComplexDetailPanelProps) {
  const scrollAnalytics = useDetailScrollAnalytics('COMPLEX', detail.complexId)
  const { areaUnit, toggleAreaUnit } = useAreaUnit()
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
  const scrollRef = useRef<HTMLDivElement>(null)
  const housingTypeIdPrefix = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const validImages = detail.images.filter(isSafeHttpUrl)
  const validOverviewImage = toHttpUrl(detail.overviewImageUrl)
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
    const activeTab = activeHousingTypeId === undefined ? undefined : choiceRefs.current.get(activeHousingTypeId)
    if (activeTab === document.activeElement) {
      revealTab(scrollRef.current, activeTab)
    }
  }, [detail.complexId, activeHousingTypeId])

  function selectHousingType(housingTypeId: string) {
    if (housingTypeId !== activeHousingTypeId) captureProductEvent('housing_type_selected', {
      complex_id: detail.complexId, housing_type_id: housingTypeId,
      detail_visit_id: detailVisitId(headingRef.current),
    })
    setSelection({ complexId: detail.complexId, housingTypeId })
    revealHousingType(housingTypeId)
  }

  function revealHousingType(housingTypeId: string) {
    revealTab(scrollRef.current, choiceRefs.current.get(housingTypeId))
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
    if (event.key !== 'Escape' || event.defaultPrevented) {
      return
    }
    event.stopPropagation()
    event.preventDefault()
    const closeCurrentView = onEscape ?? onClose
    closeCurrentView()
  }

  return (
    <aside
      className={styles.panel}
      aria-label={`${detail.name} 단지 상세 정보`}
      onKeyDown={handlePanelKeyDown}
    >
      {/* 공고 상세와 같은 머리: 분류 칩 줄, 제목, 닫기. 단지명은 머리에만 둔다. */}
      <header className={styles.header}>
        {backButton}
        <div className={styles.headerMain}>
          <p className={styles.identityType}>
            {(agencyCode || detail.agencyName) && (
              <strong data-agency={agencyCode} title={detail.agencyName}>{agencyCode ?? detail.agencyName}</strong>
            )}
            <span>{detail.rentalTypeLabel}</span>
          </p>
          <h2 ref={headingRef} id={titleId} tabIndex={-1}>{detail.name}</h2>
        </div>
        <DetailCloseButton label="단지 상세 닫기" onClose={onClose} />
      </header>

      <div
        ref={scrollRef}
        className={styles.scroll}
        {...scrollAnalytics}
        role="region"
        aria-label={`${detail.name} 단지 상세 내용`}
        tabIndex={0}
      >
        <section className={styles.identity} aria-labelledby={titleId}>
          {coverImages.length > 0 && (
            <ComplexImages
              name={detail.name}
              urls={coverImages}
              overview={usesOverviewAsCover}
            />
          )}
          <p className={styles.address}>{displayAddress(detail)}</p>
          {/* 단지 단위 행동(알림·거리뷰)은 같은 모양·높이의 보조 버튼 한 쌍으로 둔다. */}
          <div className={styles.identityActions}>
            <NotificationInterestButton
              target={{ type: 'COMPLEX', id: detail.complexId, name: detail.name }}
              source="COMPLEX_DETAIL"
            />
            {streetView && <StreetViewEntry controller={streetView} name={detail.name} appearance="action" />}
          </div>
        </section>

        <CurrentAnnouncements
          announcements={detail.currentAnnouncements}
          onOpenAnnouncement={onOpenAnnouncement}
        />

        <BasicInformation detail={detail} />

        {selectedHousingType && (
          <>
            <DetailSection appearance="block" title="주택형별 임대조건"
              actions={<AreaUnitToggle unit={areaUnit} onToggle={toggleAreaUnit} />}>
              {/* 한 줄에 주택형 하나·조건 하나. 왼쪽은 주택형, 오른쪽은 보증금·월 임대료를 위아래로 둔다. */}
              <ul className={styles.rentList} aria-label="주택형별 임대조건 비교">
                {detail.housingTypes.flatMap((housingType) => {
                  const area = formatArea(housingType.exclusiveArea, areaUnit, '')
                  const hasBasic = housingType.basicRentalCondition !== null
                  return rentalComparisonConditions(housingType).map((condition, conditionIndex) => {
                    const parts = [area, ...comparisonConditionParts(condition, hasBasic)].filter(Boolean)
                    return (
                      <li key={`${housingType.housingTypeId}-${conditionIndex}`}>
                        <div className={styles.rentType}>
                          <strong>{housingTypeName(housingType)}</strong>
                          {parts.length > 0 && (
                            <span>
                              {parts.map((part, index) => (
                                <Fragment key={part}>{index > 0 && ' · '}<span>{part}</span></Fragment>
                              ))}
                            </span>
                          )}
                        </div>
                        <dl className={styles.rentMoney}>
                          <dt>보증금</dt>
                          <dd data-emphasis={Number.isFinite(condition?.deposit) || undefined}>
                            <DetailMoney value={condition?.deposit ?? null} />
                          </dd>
                          <dt>월 임대료</dt>
                          <dd><DetailMoney value={condition?.monthlyRent ?? null} /></dd>
                        </dl>
                      </li>
                    )
                  })
                })}
              </ul>
            </DetailSection>
            <DetailSection appearance="block" title="주택형 정보">
              <div
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
                    {selected && <HousingTypePanel housingType={housingType} areaUnit={areaUnit} onToggleAreaUnit={toggleAreaUnit} />}
                  </div>
                )
              })}
            </DetailSection>
          </>
        )}

        {validOverviewImage && !usesOverviewAsCover && (
          <DetailSection appearance="block" title="단지 조감도">
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
      appearance="block"
      title="현재 모집 공고"
    >
      {announcements.length === 0 && (
        <>
          <p className={styles.empty}>지금 모집 중인 공고가 없어요.</p>
          <p className={styles.emptyHint}>알림을 켜 두면 새 공고가 올라올 때 알려드려요.</p>
        </>
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
  const title = announcement.title ?? ''
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
          {/* 원공고는 따로 표시하지 않고, 정정공고만 빨간 딱지로 알린다. */}
          {announcement.publicationTypeLabel === '정정공고' && <span className={styles.correctionBadge}>정정</span>}
          {announcementDDay(announcement) && <p className={styles.announcementDeadline} data-tone={deadlineTone}>
            {announcement.applicationStatus !== 'BEFORE_APPLICATION' && (
              <span className={styles.visuallyHidden}>마감까지 </span>
            )}
            <span>{announcementDDay(announcement)}</span>
          </p>}
        </div>
        <h4>{title}</h4>
      </header>
      <div className={styles.announcementBody}>
        <DetailFacts columns={2} appearance="list">
          <DetailFact term="접수 시작" value={displayDate(announcement.applicationStartAt)} />
          <DetailFact term="접수 마감" value={displayDate(announcement.applicationEndAt)} />
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
      appearance="block"
      title="단지 기본 정보"
    >
      <DetailFacts columns={2} appearance="list">
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
  areaUnit,
  onToggleAreaUnit,
}: {
  housingType: HousingComplexDetailHousingType
  areaUnit: AreaUnit
  onToggleAreaUnit: () => void
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

      <div className={styles.housingTypeDetailHeading}>
        <h4>선택 주택형 상세</h4>
        <AreaUnitToggle unit={areaUnit} onToggle={onToggleAreaUnit} />
      </div>
      <DetailFacts columns={2} appearance="list">
        <DetailFact term="주택형" value={name} />
        <DetailFact term="전용 면적" value={formatArea(housingType.exclusiveArea, areaUnit, '')} />
        <DetailFact term="공급 면적" value={formatArea(housingType.supplyArea, areaUnit, '')} />
        <DetailFact term="복층여부" value={duplexLabel(housingType.isDuplex)} />
        <DetailFact
          term="관리비"
          value={formatDetailMoney(housingType.maintenanceFee)}
          emphasis={Number.isFinite(housingType.maintenanceFee)}
        />
      </DetailFacts>

      <BasicRentalCondition condition={housingType.basicRentalCondition} />

      <h4>현재 공급 조건</h4>
      {housingType.currentSupplyConditions.length > 0 && (
        <ul className={styles.supplyList}>
          {housingType.currentSupplyConditions.map((condition, index) => (
            <li key={`${condition.target ?? 'unknown'}-${index}`}>
              <strong>{condition.target ?? ''}</strong>
              <SupplyConditionFacts housingTypeName={name} condition={condition} />
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function BasicRentalCondition({ condition }: { condition: ComplexBasicRentalCondition | null }) {
  const sourceUrl = toHttpUrl(condition?.sourceUrl ?? null)
  const sourceLabel = condition?.source === 'MYHOME' ? '마이홈' : condition?.source
  const collectedAt = condition?.collectedAt ?? null
  const collectedDateLabel = formatRentalConditionCollectedDate(collectedAt)

  return (
    <>
      <h4>기본 임대조건</h4>
      <DetailFacts columns={2} appearance="list">
        <DetailFact term="기본 보증금" value={formatDetailMoney(condition?.deposit ?? null)} emphasis={Number.isFinite(condition?.deposit)} />
        <DetailFact term="기본 월 임대료" value={formatDetailMoney(condition?.monthlyRent ?? null)} emphasis={Number.isFinite(condition?.monthlyRent)} />
      </DetailFacts>
      {(sourceLabel || collectedDateLabel) && (
        <p className={styles.rentalConditionSource}>
          {sourceLabel && <>출처: {sourceUrl
            ? <a href={sourceUrl} target="_blank" rel="noopener noreferrer">{sourceLabel}</a>
            : <span>{sourceLabel}</span>}</>}
          {sourceLabel && collectedDateLabel && ' · '}
          {collectedDateLabel && <>수집일: <time dateTime={collectedAt ?? undefined}>{collectedDateLabel}</time></>}
        </p>
      )}
    </>
  )
}

/**
 * 기본 임대조건 행은 조건 이름을, 현재 공급 행은 공급 대상을 보여 준다.
 * 같은 주택형에 기본 임대조건 행이 함께 있으면 현재 공급임을 함께 밝혀 두 행을 구분한다.
 */
function comparisonConditionParts(
  condition: ReturnType<typeof rentalComparisonConditions>[number],
  hasBasic: boolean,
): readonly string[] {
  if (condition === null) return []
  if (condition.label !== '현재 공급') return [condition.label]
  if (condition.target === null) return [condition.label]
  return hasBasic ? [condition.label, condition.target] : [condition.target]
}

function rentalComparisonConditions(housingType: HousingComplexDetailHousingType) {
  const basic = housingType.basicRentalCondition
  const conditions = [
    ...(basic === null ? [] : [{
      label: '기본 임대조건', target: null, deposit: basic.deposit, monthlyRent: basic.monthlyRent,
    }]),
    ...housingType.currentSupplyConditions.map((condition) => ({ ...condition, label: '현재 공급' })),
  ]
  return conditions.length > 0 ? conditions : [null]
}

function SupplyConditionFacts({
  housingTypeName,
  condition,
}: {
  housingTypeName: string
  condition: HousingComplexDetailSupplyCondition
}) {
  return (
    <div role="group" aria-label={`${housingTypeName} ${condition.target ?? ''} 현재 공급 조건`}>
      <DetailFacts columns={2} appearance="list">
        <DetailFact term="임대보증금" value={formatDetailMoney(condition.deposit)} emphasis={Number.isFinite(condition.deposit)} />
        <DetailFact term="월 임대료" value={formatDetailMoney(condition.monthlyRent)} emphasis={Number.isFinite(condition.monthlyRent)} />
        <DetailFact
          term="전환 가능 보증금"
          value={formatDetailMoney(condition.convertibleDeposit)}
          emphasis={Number.isFinite(condition.convertibleDeposit)}
          wide
        />
      </DetailFacts>
    </div>
  )
}

function revealTab(scrollContainer: HTMLDivElement | null, tab: HTMLButtonElement | undefined) {
  if (!scrollContainer || !tab) return
  const viewport = scrollContainer.getBoundingClientRect()
  const choice = tab.getBoundingClientRect()
  if (choice.top < viewport.top) {
    scrollContainer.scrollTop += choice.top - viewport.top
  } else if (choice.bottom > viewport.bottom) {
    scrollContainer.scrollTop += choice.bottom - viewport.bottom
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
    { label: '평면도', url: toHttpUrl(housingType.floorPlanImageUrl) },
    { label: '3D 평면도', url: toHttpUrl(housingType.floorPlan3dImageUrl) },
  ].filter((image): image is { label: string; url: string } => image.url !== null)
}

function housingTypeName(housingType: HousingComplexDetailHousingType) {
  return housingType.name ?? ''
}

function isSafeHttpUrl(value: string) {
  return toHttpUrl(value) !== null
}

function displayAddress(detail: HousingComplexDetailData) {
  return detail.roadAddress || detail.regionName || ''
}

function displayDate(value: string | null) {
  return formattedDate(value) ?? ''
}

function formattedDate(value: string | null) {
  if (value === null || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  return value.replaceAll('-', '.')
}

function availability(value: boolean | null) {
  if (value === null) {
    return ''
  }
  return value ? '있음' : '없음'
}

function duplexLabel(value: boolean | null) {
  if (value === null) {
    return ''
  }
  return value ? '복층' : '해당 없음'
}

function formatNullableCount(value: number | null, suffix: string) {
  if (value === null || !Number.isFinite(value)) {
    return ''
  }
  return `${formatNumber(value)}${suffix}`
}

function parkingSummary(parkingCount: number | null, householdCount: number | null) {
  if (parkingCount === null || !Number.isFinite(parkingCount)) {
    return ''
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

function formatNumber(value: number) {
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

function applicationStatusLabel(status: string | null) {
  if (status === 'BEFORE_APPLICATION') {
    return '공고중'
  }
  if (status === 'APPLYING') {
    return '접수중'
  }
  if (status === 'CLOSED') {
    return '접수마감'
  }
  return ''
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
    return ''
  }
  if (announcement.dDay < 0) {
    return ''
  }
  return announcement.applicationStatus === 'BEFORE_APPLICATION'
    ? `접수 시작 D-${announcement.dDay}`
    : `D-${announcement.dDay}`
}

function isUrgent(announcement: HousingComplexDetailAnnouncement) {
  if (announcement.applicationStatus !== 'APPLYING' || announcement.dDay === null) {
    return false
  }
  return Number.isInteger(announcement.dDay)
    && announcement.dDay >= 0
    && announcement.dDay <= 3
}

/** 비교 목록의 금액. 값이 없으면 빈칸 대신 흐린 결측 문구를 둔다. */
function DetailMoney({ value }: { readonly value: number | null | undefined }) {
  const formatted = formatDetailMoney(value)
  return formatted === '' ? <span className={styles.missingValue}>{MISSING_DATA_LABEL}</span> : <>{formatted}</>
}

/** 단지 상세 금액은 억·만 단위로 읽는 순서대로 표시하고 버리지 않는다. */
function formatDetailMoney(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) || value < 0 ? '' : formatHousingMoneyReadable(value)
}
