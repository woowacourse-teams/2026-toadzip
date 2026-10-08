import type { MapMarkerAmount } from '../../public-housing/presentation/mapMarkerPresentation.ts'
import { MISSING_DATA_LABEL } from '../../public-housing/presentation/missingData.ts'
import type { NaverMapComplexMarker } from './naverMapTypes.ts'

export const COMPLEX_MARKER_ANCHOR_X = 16

/** SDK 연결 없이도 서비스와 같은 마커 표시를 재사용한다. */
export function createComplexMarkerButton(marker: NaverMapComplexMarker): HTMLButtonElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = markerClassName(marker)
  const width = markerWidth(marker)
  button.style.setProperty('--marker-width', `${width}px`)
  button.style.setProperty('--marker-anchor-x', `${COMPLEX_MARKER_ANCHOR_X}px`)
  button.setAttribute('aria-label', markerAriaLabel(marker))
  button.setAttribute('aria-pressed', String(Boolean(marker.selected)))
  button.dataset.complexId = marker.id
  button.dataset.mapComplexMarker = 'true'
  if (marker.applicationStatus) {
    button.dataset.applicationStatus = marker.applicationStatus
  }
  const card = document.createElement('span')
  card.className = 'housing-map-marker__card'
  const surface = document.createElement('span')
  surface.className = 'housing-map-marker__surface'
  surface.setAttribute('aria-hidden', 'true')
  const content = document.createElement('span')
  content.className = 'housing-map-marker__content'
  content.append(
    createMarkerTop(marker),
    createMarkerBody(marker),
  )
  card.append(surface, content)
  button.append(card)
  return button
}

export function markerWidth(marker: NaverMapComplexMarker) {
  // Reserve room for every digit, the full unit, row label, and padding.
  // Keep the SDK overlay size in sync with the visible button.
  const amountWidths = [marker.deposit, marker.monthlyRent].map((amount) => (
    amount === null ? 0 : amount.digits.length * 7.5 + amount.unit.length * 12 + 32
  ))
  return Math.ceil(Math.max(92, ...amountWidths))
}

function markerAriaLabel(marker: NaverMapComplexMarker) {
  return `${markerSummary(marker)}, 단지 상세 보기`
}

export function markerSummary(marker: NaverMapComplexMarker) {
  const metadata = marker.agencyName === MISSING_DATA_LABEL
    && marker.rentalTypeName === MISSING_DATA_LABEL
    ? `공급기관 및 임대유형 ${MISSING_DATA_LABEL}`
    : `${marker.agencyName} · ${marker.rentalTypeName}`
  const statusLabel = markerStatusLabel(marker)
  return [
    marker.name,
    statusLabel,
    metadata,
    `전용면적 ${marker.exclusiveAreaLabel ?? MISSING_DATA_LABEL}`,
    `보증금 ${markerAmountSummary(marker.deposit)}`,
    `월 임대료 ${markerAmountSummary(marker.monthlyRent)}`,
  ].filter((part) => part !== null).join(', ')
}

function markerStatusLabel(marker: NaverMapComplexMarker) {
  return marker.applicationStatus === 'APPLYING'
    ? '접수중'
    : marker.applicationStatus === 'BEFORE_APPLICATION' ? '공고중' : null
}

function markerAmountSummary(amount: MapMarkerAmount | null) {
  return amount === null ? MISSING_DATA_LABEL : `최소 ${amount.exactLabel}`
}

function createMarkerTop(marker: NaverMapComplexMarker) {
  const top = document.createElement('span')
  top.className = 'housing-map-marker__top'
  const title = document.createElement('span')
  title.className = 'housing-map-marker__title-reveal'
  title.append(createMarkerText('title', marker.name))
  top.append(title)
  const compact = document.createElement('span')
  compact.className = 'housing-map-marker__compact'
  const metadata = document.createElement('span')
  metadata.className = 'housing-map-marker__metadata'
  compact.append(metadata)
  top.append(compact)
  const agencyMissing = marker.agencyLabel === MISSING_DATA_LABEL
  const rentalTypeMissing = marker.rentalTypeLabel === MISSING_DATA_LABEL
  if (agencyMissing && rentalTypeMissing) {
    metadata.dataset.missingSummary = 'true'
    metadata.append(createMarkerText('name', MISSING_DATA_LABEL))
    return top
  }
  if (agencyMissing || rentalTypeMissing) {
    metadata.dataset.missingPosition = agencyMissing ? 'first' : 'last'
  }
  metadata.append(
    createMarkerText('name', marker.agencyLabel),
    createMarkerText('name', marker.rentalTypeLabel),
  )
  return top
}

function createMarkerBody(marker: NaverMapComplexMarker) {
  const body = document.createElement('span')
  body.className = 'housing-map-marker__body'
  const details = document.createElement('span')
  details.className = 'housing-map-marker__details'
  const detailContent = document.createElement('span')
  detailContent.className = 'housing-map-marker__detail-content'
  const summaryRow = document.createElement('span')
  summaryRow.className = 'housing-map-marker__summary-row'
  const metadata = createMarkerText('summary', '')
  if (marker.agencyLabel === MISSING_DATA_LABEL && marker.rentalTypeLabel === MISSING_DATA_LABEL) {
    metadata.textContent = MISSING_DATA_LABEL
  } else {
    const agency = createMarkerText('agency', marker.agencyLabel)
    agency.dataset.agency = marker.agencyLabel
    metadata.append(agency, ` · ${marker.rentalTypeLabel}`)
  }
  summaryRow.append(metadata)
  const status = markerStatusLabel(marker)
  if (status) summaryRow.append(createMarkerText('status', status))
  detailContent.append(
    summaryRow,
    createMarkerText('area', `전용 ${marker.exclusiveAreaLabel ?? MISSING_DATA_LABEL}`),
  )
  details.append(detailContent)
  body.append(details)
  body.append(
    createMarkerAmountRow('보', marker.deposit),
    createMarkerAmountRow('월', marker.monthlyRent),
  )
  return body
}

function createMarkerAmountRow(label: string, amount: MapMarkerAmount | null) {
  const row = document.createElement('span')
  row.className = 'housing-map-marker__row'
  row.append(createMarkerText('label', label))
  if (amount === null) {
    row.append(createMarkerText('missing', '-'))
    return row
  }
  const value = document.createElement('span')
  value.className = 'housing-map-marker__amount'
  value.append(
    createMarkerText('digits', amount.digits),
    createMarkerText('unit', amount.unit),
    createMarkerText('from', '~'),
  )
  row.append(value)
  return row
}

function createMarkerText(className: string, text: string) {
  const node = document.createElement('span')
  node.className = `housing-map-marker__${className}`
  node.textContent = text
  return node
}

function markerClassName(marker: NaverMapComplexMarker) {
  return [
    'housing-map-marker',
    marker.selected ? 'is-selected' : '',
    marker.highlighted ? 'is-highlighted' : '',
  ].filter(Boolean).join(' ')
}
