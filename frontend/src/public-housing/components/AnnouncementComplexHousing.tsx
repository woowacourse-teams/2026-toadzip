import { useId, useState } from 'react'
import type { AnnouncementSupplyTarget, SharedTargetCount } from '../model/publicHousing.ts'
import type { HousingAnnouncementSupplyComplexGroup } from '../presentation/announcementDetailPresentation.ts'
import { formatHousingArea as formatArea, type AreaUnit } from '../presentation/housingArea'
import { formatHousingMoney, formatHousingMoneyReadable } from '../presentation/housingMoney'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { toHttpUrl } from '../presentation/httpUrl.ts'
import { AreaUnitToggle } from './AreaUnitToggle'
import { DetailSection } from './DetailPrimitives.tsx'
import type { HousingAnnouncementDetailSupplyRow } from './HousingAnnouncementDetailPanel.tsx'
import styles from './AnnouncementComplexHousing.module.css'

export interface FloorPlanSelection {
  readonly complexName: string
  readonly housingTypeName: string
  readonly twoDimensionalUrl: string | null
  readonly threeDimensionalUrl: string | null
}

interface AnnouncementComplexHousingProps {
  readonly areaUnit: AreaUnit
  readonly onToggleAreaUnit: () => void
  readonly groups: readonly HousingAnnouncementSupplyComplexGroup[]
  readonly supplyHouseholdCount: number | null
  /** 세대수가 공고문으로 보정되지 않아 예비 입주자가 포함될 수 있는지 */
  readonly waitlistMayBeIncluded?: boolean
  readonly onOpenComplex?: (complexId: string) => void
  readonly onOpenFloorPlan: (selection: FloorPlanSelection) => void
}

/** 단지마다 주택형 표를 보이고, 신청 대상을 고르면 표를 그 대상의 금액으로 바꾼다. */
export function AnnouncementComplexHousing({
  areaUnit,
  onToggleAreaUnit,
  groups,
  supplyHouseholdCount,
  waitlistMayBeIncluded = false,
  onOpenComplex,
  onOpenFloorPlan,
}: AnnouncementComplexHousingProps) {
  const [target, setTarget] = useState<string | null>(null)
  const targetNames = distinctTargetNames(groups)
  const chosen = targetNames.length > 1 && target !== null && targetNames.includes(target) ? target : null
  const cards = groups.map((group) => ({ group, rows: pickRows(group, chosen) }))
  const available = cards.filter(({ rows }) => chosen === null || rows.length > 0)
  // 대상을 고르면 신청할 수 있는 단지를 먼저 보이고, 각 묶음 안에서는 공고 순서를 지킨다.
  const ordered = chosen === null ? cards : [...available, ...cards.filter((card) => !available.includes(card))]

  return (
    <DetailSection appearance="block"
      title="단지·주택형"
      actions={groups.length > 0 ? <AreaUnitToggle unit={areaUnit} onToggle={onToggleAreaUnit} /> : undefined}
      aside={`${groups.length.toLocaleString('ko-KR')}개 단지 · ${supplyHouseholdSummary(supplyHouseholdCount)}`}
    >
      {groups.length === 0 && <p className={styles.empty}>단지 정보: {MISSING_DATA_LABEL}</p>}
      {groups.length > 0 && waitlistMayBeIncluded && (
        <p className={styles.countNote}>세대수는 공고 기관이 제공한 수치라 예비 입주자가 포함될 수 있어요. 정확한 수는 공고문에서 확인해 주세요.</p>
      )}
      {targetNames.length > 1 && (
        <TargetChips names={targetNames} chosen={chosen} onChoose={setTarget} />
      )}
      {groups.length > 0 && (
        <ul className={styles.cards}>
          {ordered.map(({ group, rows }) => (
            <ComplexCard key={group.key} group={group} rows={rows} areaUnit={areaUnit} chosen={chosen}
              initiallyOpen={groups.length === 1} onOpenComplex={onOpenComplex} onOpenFloorPlan={onOpenFloorPlan} />
          ))}
        </ul>
      )}
    </DetailSection>
  )
}

function TargetChips({ names, chosen, onChoose }: {
  names: readonly string[]
  chosen: string | null
  onChoose: (name: string | null) => void
}) {
  const labelId = useId()
  return (
    <div className={styles.chips}>
      <p id={labelId}>신청 대상을 고르면 그 금액으로 보여요</p>
      <div role="group" aria-labelledby={labelId}>
        {[null, ...names].map((name) => (
          <button key={name ?? '__all'} type="button" aria-pressed={name === chosen}
            onClick={() => onChoose(name)}>
            {name ?? '전체'}
          </button>
        ))}
      </div>
    </div>
  )
}

function ComplexCard({ group, rows, areaUnit, chosen, initiallyOpen, onOpenComplex, onOpenFloorPlan }: {
  group: HousingAnnouncementSupplyComplexGroup
  rows: readonly PickedRow[]
  areaUnit: AreaUnit
  chosen: string | null
  initiallyOpen: boolean
  onOpenComplex?: (complexId: string) => void
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const [open, setOpen] = useState(initiallyOpen)
  const detailId = useId()
  const imageUrl = toHttpUrl(group.overviewImageUrl)
  const unavailable = chosen !== null && rows.length === 0
  const canOpenComplex = group.complexId !== null && onOpenComplex !== undefined

  return (
    <li className={styles.card} data-unavailable={unavailable || undefined}>
      <article aria-label={`${group.name} 단지`}>
        {imageUrl && (
          <img className={styles.image} src={imageUrl} alt={`${group.name} 단지 조감도`} loading="lazy" />
        )}
        <header className={styles.cardHeading}>
          {group.supplyHouseholdCount !== null && (
            <span className={styles.countTile}>
              <b>{group.supplyHouseholdCount.toLocaleString('ko-KR')}</b>
              <span>세대</span>
            </span>
          )}
          <h4>{group.name}</h4>
        </header>
        <p className={styles.address} data-missing={group.address === null || undefined}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
            <path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z" />
            <circle cx="12" cy="9.5" r="2.5" />
          </svg>
          <span>{group.address ?? `주소: ${MISSING_DATA_LABEL}`}</span>
        </p>

        {unavailable ? (
          <p className={styles.unavailable}>‘{chosen}’은(는) 이 단지에 신청할 수 없어요</p>
        ) : (
          <table className={styles.table}>
            <caption>{group.name} 주택형별 금액</caption>
            <thead>
              <tr>
                <th scope="col">면적</th>
                <th scope="col">세대</th>
                <th scope="col">보증금</th>
                <th scope="col">월 임대료</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ row, targets }) => (
                <tr key={row.supplyRowId}>
                  <th scope="row">
                    <b className={styles.area}>{formatArea(exclusiveArea(row), areaUnit)}</b>
                    <span title={housingTypeLabel(row)}>{housingTypeLabel(row)}</span>
                  </th>
                  <td title={row.sharedRecruitment ? '여러 주택형의 공통 모집 · 개별 세대수 미확정' : undefined}>
                    {row.sharedRecruitment ? '공통' : formatCount(row.totalSupplyHouseholdCount, '')}
                  </td>
                  <MoneyCell targets={targets} field="deposit" />
                  <MoneyCell targets={targets} field="monthlyRent" />
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className={styles.actions} data-single={!canOpenComplex || undefined}>
          {canOpenComplex && (
            <button type="button" className={styles.complexButton}
              data-detail-return-focus={`complex:${group.complexId}`}
              aria-label={`${group.name} 단지 상세 보기`}
              onClick={() => onOpenComplex(group.complexId!)}>
              단지 상세 보기 <ChevronIcon direction="right" />
            </button>
          )}
          <button type="button" className={styles.toggleButton} aria-expanded={open} aria-controls={detailId}
            onClick={() => setOpen((value) => !value)}>
            {open ? '접기' : '전체 대상 보기'} <ChevronIcon direction={open ? 'up' : 'down'} />
          </button>
        </div>

        <div id={detailId} className={styles.detail} hidden={!open}>
          {open && group.rows.map((row) => (
            <HousingTypeDetail key={row.supplyRowId} complexName={group.name} row={row} areaUnit={areaUnit}
              chosen={chosen} onOpenFloorPlan={onOpenFloorPlan} />
          ))}
        </div>
      </article>
    </li>
  )
}

function MoneyCell({ targets, field }: {
  targets: readonly AnnouncementSupplyTarget[]
  field: 'deposit' | 'monthlyRent'
}) {
  const values = targets.map((target) => target[field]).filter(isFiniteNumber)
  if (values.length === 0) {
    return <td data-missing="true">{MISSING_DATA_LABEL}</td>
  }
  const lowest = Math.min(...values)
  // 비교 표는 한 줄에 들도록 만원·억 소수 표기를 쓰고, 원 단위 금액은 펼친 상세와 title에서 보인다.
  // 단어 결합자(U+2060)로 '~'가 금액과 떨어져 혼자 줄을 바꾸지 않게 한다.
  return (
    <td title={formatHousingMoneyReadable(lowest)}>
      {field === 'monthlyRent' ? formatRentInTable(lowest) : formatHousingMoney(lowest)}
      {values.some((value) => value !== lowest) ? '\u2060~' : ''}
    </td>
  )
}

/** 월 임대료는 비교 표에서 만원 소수 첫째 자리까지 반올림해 보인다. 예: 226,670원 → 22.7만원 */
function formatRentInTable(value: number) {
  if (value < 10_000) return formatHousingMoney(value)
  return `${(value / 10_000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만원`
}

function HousingTypeDetail({ complexName, row, areaUnit, chosen, onOpenFloorPlan }: {
  complexName: string
  row: HousingAnnouncementDetailSupplyRow
  areaUnit: AreaUnit
  chosen: string | null
  onOpenFloorPlan: (selection: FloorPlanSelection) => void
}) {
  const name = housingTypeLabel(row)
  const twoDimensionalUrl = toHttpUrl(row.housingType?.floorPlanImageUrl ?? null)
  const threeDimensionalUrl = toHttpUrl(row.housingType?.floorPlan3dImageUrl ?? null)
  // 공고가 공급면적을 명시적으로 비운 경우(null)도 '공고문 확인'으로 보이고 단지 기본값으로 채우지 않는다.
  const supplyArea = row.supplyArea === undefined ? row.housingType?.supplyArea ?? null : row.supplyArea
  const showsSupplyArea = row.supplyArea !== undefined || row.housingType?.supplyArea != null
  const targets = visibleTargets(row)

  return (
    <article className={styles.housingType} aria-label={`${complexName} ${name} 주택형`}>
      <header>
        <div>
          <strong>{formatArea(exclusiveArea(row), areaUnit)} <span>{name} · {row.supplyTypeLabel}</span></strong>
          <span>{row.sharedRecruitment ? '공통 합계 · 개별 미확정' : formatCount(row.totalSupplyHouseholdCount, '세대')}</span>
        </div>
        {(twoDimensionalUrl || threeDimensionalUrl) && (
          <button type="button" aria-label={`${complexName} ${name} 평면도 보기`}
            onClick={() => onOpenFloorPlan({ complexName, housingTypeName: name, twoDimensionalUrl, threeDimensionalUrl })}>
            평면도 보기
          </button>
        )}
      </header>
      {(showsSupplyArea || row.occupancyExpectedYearMonth) && (
        <dl className={styles.housingFacts}>
          {showsSupplyArea && <div><dt>공급면적</dt><dd>{formatArea(supplyArea, areaUnit)}</dd></div>}
          {row.occupancyExpectedYearMonth && (
            <div><dt>최초 입주 연월</dt><dd>{formatYearMonth(row.occupancyExpectedYearMonth)}</dd></div>
          )}
        </dl>
      )}
      {row.occupancyExpectedYearMonth && (
        <p className={styles.note}>최초 입주 연월은 원문 기준이며, 이번 모집의 입주 일정과 다를 수 있어요.</p>
      )}
      {targets.length > 0 && (
        <>
        <div className={styles.targetHead} aria-hidden="true"><span>대상</span><span>보증금</span><span>월 임대료</span></div>
        <ul className={styles.targets} aria-label="대상별 공급 조건">
          {targets.map((target) => (
            <li key={target.supplyTargetId} data-chosen={targetName(target) === chosen || undefined}>
              <div className={styles.targetMain}>
                <strong>{targetName(target)}{hasText(target.priority) && <span> · {target.priority}</span>}</strong>
                <span aria-label={`보증금 ${formatHousingMoneyReadable(target.deposit)}`}>{formatHousingMoneyReadable(target.deposit)}</span>
                <span aria-label={`월 임대료 ${formatHousingMoneyReadable(target.monthlyRent)}`}>{formatHousingMoneyReadable(target.monthlyRent)}</span>
              </div>
              <TargetMeta target={target} />
            </li>
          ))}
        </ul>
        </>
      )}
    </article>
  )
}

function ChevronIcon({ direction }: { direction: 'right' | 'down' | 'up' }) {
  const path = { right: 'M9 6l6 6-6 6', down: 'M6 9l6 6 6-6', up: 'M6 15l6-6 6 6' }[direction]
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" focusable="false">
      <path d={path} />
    </svg>
  )
}

function distinctTargetNames(groups: readonly HousingAnnouncementSupplyComplexGroup[]) {
  const names: string[] = []
  for (const group of groups) {
    for (const row of group.rows) {
      for (const target of visibleTargets(row)) {
        const name = target.target?.trim()
        if (name && !names.includes(name)) names.push(name)
      }
    }
  }
  return names
}

interface PickedRow {
  readonly row: HousingAnnouncementDetailSupplyRow
  readonly targets: readonly AnnouncementSupplyTarget[]
}

/** 고른 대상이 없는 주택형 행은 빼고, 남은 행마다 표에 쓸 대상을 고른다. */
function pickRows(group: HousingAnnouncementSupplyComplexGroup, chosen: string | null): readonly PickedRow[] {
  return group.rows
    .map((row) => ({ row, targets: chosenTargets(row, chosen) }))
    .filter(({ targets }) => chosen === null || targets.length > 0)
}

function chosenTargets(row: HousingAnnouncementDetailSupplyRow, chosen: string | null) {
  const targets = visibleTargets(row)
  return chosen === null ? targets : targets.filter((target) => targetName(target) === chosen)
}

function visibleTargets(row: HousingAnnouncementDetailSupplyRow) {
  return row.targets.filter((target) => (
    [target.target, target.priority].some(hasText)
    || [target.supplyHouseholdCount, target.waitlistCount, target.deposit, target.monthlyRent].some(isFiniteNumber)
    || target.sharedSupplyHouseholdCount != null || target.sharedWaitlistCount != null
  ))
}

function TargetMeta({ target }: { target: AnnouncementSupplyTarget }) {
  const parts = [
    labeledCount('공급', target.supplyHouseholdCount, target.sharedSupplyHouseholdCount, '세대', target.target),
    labeledCount('예비자', target.waitlistCount, target.sharedWaitlistCount, '명', target.target),
  ].filter((part) => part !== null)
  return parts.length > 0 ? <p className={styles.targetMeta}>{parts.join(' · ')}</p> : null
}

/** 다른 대상과 함께 쓰는 수는 대상마다 나누지 않고 '예비자 공통 20명 · 청년(소득 있음)과 함께'로 보인다. */
function labeledCount(label: string, own: number | null, shared: SharedTargetCount | null | undefined, unit: string, self: string | null) {
  if (shared) {
    const others = shared.targets.filter((name) => name !== self)
    return `${label} 공통 ${shared.count.toLocaleString('ko-KR')}${unit}${others.length > 0 ? ` · ${others.join('·')}과 함께` : ''}`
  }
  return isFiniteNumber(own) ? `${label} ${own.toLocaleString('ko-KR')}${unit}` : null
}

function targetName(target: AnnouncementSupplyTarget) {
  return target.target?.trim() || MISSING_DATA_LABEL
}

function exclusiveArea(row: HousingAnnouncementDetailSupplyRow) {
  return row.exclusiveArea === undefined ? row.housingType?.exclusiveArea ?? null : row.exclusiveArea
}

function housingTypeLabel(row: HousingAnnouncementDetailSupplyRow) {
  // 공고 표시명 → 단지 주택형명 → 원천 순서는 서버가 정한다.
  return row.displayHousingTypeName ?? row.housingType?.name ?? row.sourceHousingTypeName ?? MISSING_DATA_LABEL
}

function formatCount(value: number | null, unit: string) {
  return value === null ? MISSING_DATA_LABEL : `${value.toLocaleString('ko-KR')}${unit}`
}

function supplyHouseholdSummary(value: number | null) {
  return value === null ? `공급 세대수: ${MISSING_DATA_LABEL}` : formatCount(value, '세대')
}

function formatYearMonth(value: string) {
  const match = /^(\d{4})-?(\d{2})/.exec(value)
  return match ? `${match[1]}.${match[2]}` : MISSING_DATA_LABEL
}

function isFiniteNumber(value: number | null): value is number {
  return value !== null && Number.isFinite(value)
}

function hasText(value: string | null): value is string {
  return value !== null && value.trim().length > 0
}
