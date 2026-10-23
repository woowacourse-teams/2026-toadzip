import { announcementSections, complexSections, supplyFields } from '../management/fields'

export const fieldLabels = new Map<string, string>([
  ...complexSections.flatMap(section => section.fields),
  ...announcementSections.flatMap(section => section.fields),
  ...supplyFields,
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
  return evidenceLabels[normalized] ?? fieldLabels.get(normalized) ?? fieldLabels.get(normalized.split('.').at(-1) ?? '') ?? path
}
