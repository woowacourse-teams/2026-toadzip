import type { MapMarkerAmount } from '../../public-housing/presentation/mapMarkerPresentation.ts'
import { MISSING_DATA_LABEL } from '../../public-housing/presentation/missingData.ts'
import type { NaverMapComplexMarker } from './NaverMap.tsx'

/** SDK 연결 없이도 서비스와 같은 마커 표시를 재사용한다. */
export function createComplexMarkerButton(marker: NaverMapComplexMarker): HTMLButtonElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = markerClassName(marker)
  const width = markerWidth(marker)
  button.style.setProperty('--marker-width', `${width}px`)
  button.setAttribute('aria-label', markerAriaLabel(marker))
  button.setAttribute('aria-pressed', String(Boolean(marker.selected)))
  button.dataset.complexId = marker.id
  button.dataset.mapComplexMarker = 'true'
  button.title = markerSummary(marker)
  button.append(
    createMarkerTop(marker),
    createMarkerBody(marker),
  )
  return button
}

export function markerWidth(marker: NaverMapComplexMarker) {
  // Reserve room for every digit, the full unit, row label, and padding.
  // The same width anchors the SDK overlay and its visible button.
  const amountWidths = [marker.deposit, marker.monthlyRent].map((amount) => (
    amount === null ? 0 : amount.digits.length * 9 + amount.unit.length * 12 + 37
  ))
  return Math.max(112, ...amountWidths)
}

function markerAriaLabel(marker: NaverMapComplexMarker) {
  return `${markerSummary(marker)}, 단지 상세 보기`
}

export function markerSummary(marker: NaverMapComplexMarker) {
  const metadata = marker.agencyName === MISSING_DATA_LABEL
    && marker.rentalTypeName === MISSING_DATA_LABEL
    ? `공급기관 및 임대유형 ${MISSING_DATA_LABEL}`
    : `${marker.agencyName} · ${marker.rentalTypeName}`
  return [
    marker.name,
    metadata,
    `보증금 ${markerAmountSummary(marker.deposit)}`,
    `월 임대료 ${markerAmountSummary(marker.monthlyRent)}`,
  ].join(', ')
}

function markerAmountSummary(amount: MapMarkerAmount | null) {
  return amount === null ? MISSING_DATA_LABEL : `최소 ${amount.exactLabel}`
}

function createMarkerTop(marker: NaverMapComplexMarker) {
  const top = document.createElement('span')
  top.className = 'housing-map-marker__top'
  const agencyMissing = marker.agencyLabel === MISSING_DATA_LABEL
  const rentalTypeMissing = marker.rentalTypeLabel === MISSING_DATA_LABEL
  if (agencyMissing && rentalTypeMissing) {
    top.dataset.missingSummary = 'true'
    top.append(createMarkerText('name', MISSING_DATA_LABEL))
    return top
  }
  if (agencyMissing || rentalTypeMissing) {
    top.dataset.missingPosition = agencyMissing ? 'first' : 'last'
  }
  top.append(
    createMarkerText('name', marker.agencyLabel),
    createMarkerText('name', marker.rentalTypeLabel),
  )
  return top
}

function createMarkerBody(marker: NaverMapComplexMarker) {
  const body = document.createElement('span')
  body.className = 'housing-map-marker__body'
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
    row.append(createMarkerText('missing', MISSING_DATA_LABEL))
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
