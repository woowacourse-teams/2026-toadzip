import { announcementSections, complexSections, receptionPlaceFields, supplyFields } from '../management/fields'

export const fieldLabels = new Map<string, string>([
  ...complexSections.flatMap(section => section.fields),
  ...announcementSections.flatMap(section => section.fields),
  ...supplyFields,
  // 접수처는 이제 `receptionPlaces` 목록이고, 이전 이력에는 한 곳짜리 `receptionPlace` 객체로 남아 있다.
  { name: 'receptionPlaces', label: '접수처' }, { name: 'receptionPlace', label: '접수처' },
  ...receptionPlaceFields.flatMap(field => [{ name: `receptionPlaces.${field.name}`, label: field.label }, { name: `receptionPlace.${field.name}`, label: field.label }]),
  // 이전 이력에는 관리자가 원천 이름 칸을 직접 고친 값이 남아 있다.
  { name: 'sourceComplexName', label: '원천 단지명' }, { name: 'sourceHousingTypeName', label: '원천 주택형명' },
].map(field => [field.name, field.label]))

const evidenceLabels: Record<string, string> = {
  version: '데이터 버전', 'documentEvidence.sourceUrl': 'PDF 출처', 'documentEvidence.fileName': 'PDF 파일명',
  'documentEvidence.sha256': '파일 식별값', 'documentEvidence.note': '확인 메모',
  'documentEvidence.citations.field': '확인한 항목', 'documentEvidence.citations.page': '근거 쪽수', 'documentEvidence.citations.quote': '원문 인용',
  sourceUrl: '출처 URL', fileName: 'PDF 파일명', sha256: '파일 식별값', note: '확인 메모',
  'citations.field': '확인한 항목', 'citations.page': '근거 쪽수', 'citations.quote': '원문 인용',
}

export function storedFieldLabel(path: string) {
  const normalized = path.replace(/\[\d+\]/g, '')
  // 접수처 칸은 몇 번째 접수처인지 함께 보인다. 이전 한 곳짜리 접수처는 접수처 1이다.
  const reception = /^receptionPlaces?(?:\[(\d+)\])?\.(\w+)$/.exec(path)
  const receptionLabel = reception ? fieldLabels.get(`receptionPlaces.${reception[2]}`) : undefined
  if (reception && receptionLabel) return `접수처 ${Number(reception[1] ?? 0) + 1} · ${receptionLabel}`
  return evidenceLabels[normalized] ?? fieldLabels.get(normalized) ?? fieldLabels.get(normalized.split('.').at(-1) ?? '') ?? path
}
