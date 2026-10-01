import { Link } from 'react-router'
import { useMemo, useRef, useState } from 'react'
import {
  createAnnouncementImport,
  validateAnnouncementImport,
  type AnnouncementImportValidationResponse,
} from './api'
import { registrationFailure } from './formValues'
import { RegistrationError } from './RegistrationFields'

export function AnnouncementImportForm({
  onSubmittingChange,
}: {
  onSubmittingChange: (isSubmitting: boolean) => void
}) {
  const [jsonText, setJsonText] = useState('')
  const [validatedJsonText, setValidatedJsonText] = useState<string | null>(null)
  const [validatedDocument, setValidatedDocument] = useState<unknown>(null)
  const [validation, setValidation] = useState<AnnouncementImportValidationResponse | null>(null)
  const [selections, setSelections] = useState<Readonly<Record<number, number>>>({})
  const [isValidating, setIsValidating] = useState(false)
  const [isReadingFile, setIsReadingFile] = useState(false)
  const [fileName, setFileName] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createdId, setCreatedId] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const inputVersion = useRef(0)
  const summary = useMemo(() => documentSummary(validatedDocument), [validatedDocument])
  const canSubmit = !isReadingFile && !isValidating && validation?.registerable === true
    && validation.supplyRows.every((row) => selections[row.supplyRowIndex] !== undefined)

  function changeJson(value: string) {
    inputVersion.current += 1
    setJsonText(value)
    setValidatedJsonText(null)
    setValidatedDocument(null)
    setValidation(null)
    setSelections({})
    setIsValidating(false)
    setIsReadingFile(false)
    setFileName(null)
    setError(null)
    setSuccess(null)
    setCreatedId(null)
  }

  async function readJsonFile(file: File) {
    changeJson('')
    if (!/\.json$/i.test(file.name)) {
      setError('.json 파일을 선택해 주세요.')
      return
    }
    const fileInputVersion = inputVersion.current
    setFileName(file.name)
    setIsReadingFile(true)
    try {
      const text = (await file.text()).replace(/^\uFEFF/, '')
      if (inputVersion.current !== fileInputVersion) return
      if (text.trim().length === 0) {
        setError('파일이 비어 있습니다. JSON 내용이 있는 파일을 선택해 주세요.')
        return
      }
      setJsonText(text)
    } catch {
      if (inputVersion.current === fileInputVersion) {
        setError('JSON 파일을 읽지 못했습니다. 파일을 다시 선택해 주세요.')
      }
    } finally {
      if (inputVersion.current === fileInputVersion) setIsReadingFile(false)
    }
  }

  async function validateJson() {
    if (isReadingFile || isValidating || isSubmitting) return
    let document: unknown
    try {
      document = JSON.parse(jsonText) as unknown
    } catch {
      setError('JSON 문법을 확인해 주세요.')
      return
    }
    if (!isRecord(document)) {
      setError('최상위 값은 JSON 객체여야 합니다.')
      return
    }
    const validationInputVersion = inputVersion.current
    const validationJsonText = jsonText
    setIsValidating(true)
    setError(null)
    setSuccess(null)
    try {
      const result = await validateAnnouncementImport(validationJsonText)
      if (inputVersion.current !== validationInputVersion) {
        return
      }
      setValidatedJsonText(validationJsonText)
      setValidatedDocument(document)
      setValidation(result)
      setSelections(suggestedSelections(result))
    } catch (requestError) {
      if (inputVersion.current !== validationInputVersion) {
        return
      }
      setValidatedJsonText(null)
      setValidatedDocument(null)
      setValidation(null)
      setSelections({})
      setError(registrationFailure(requestError, '공고 JSON 검증 요청을 처리하지 못했습니다.').message)
    } finally {
      if (inputVersion.current === validationInputVersion) {
        setIsValidating(false)
      }
    }
  }

  async function registerImport() {
    if (!validation || validatedJsonText === null || !canSubmit) {
      return
    }
    setIsSubmitting(true)
    onSubmittingChange(true)
    setError(null)
    setSuccess(null)
    try {
      const created = await createAnnouncementImport(
        validatedJsonText,
        validation.supplyRows.map((row) => ({
          supplyRowIndex: row.supplyRowIndex,
          housingComplexId: selections[row.supplyRowIndex],
        })),
      )
      setCreatedId(created.announcementId)
      setSuccess(
        `공고 #${created.announcementId}를 저장했습니다. 공급행 ${created.supplyRowCount}건, 일정 ${created.scheduleCount}건, 첨부 ${created.attachmentCount}건입니다.`,
      )
      setJsonText('')
      setFileName(null)
      setValidatedJsonText(null)
      setValidatedDocument(null)
      setValidation(null)
      setSelections({})
    } catch (requestError) {
      setError(registrationFailure(requestError, '공고 JSON 등록 요청을 처리하지 못했습니다.').message)
    } finally {
      setIsSubmitting(false)
      onSubmittingChange(false)
    }
  }

  return (
    <section className="registration-card" aria-labelledby="announcement-import-title">
      <h2 id="announcement-import-title">JSON 가져오기</h2>
      <p>공고 JSON을 붙여넣거나 .json 파일을 선택하고, 검증 결과와 단지 연결을 확인한 뒤 등록합니다.</p>
      <label htmlFor="announcement-import-file">공고 JSON 파일</label>
      <input
        id="announcement-import-file"
        type="file"
        accept=".json,application/json"
        disabled={isSubmitting}
        aria-describedby="announcement-import-file-help"
        onChange={(event) => {
          const file = event.currentTarget.files?.[0]
          event.currentTarget.value = ''
          if (file) void readJsonFile(file)
        }}
      />
      <p id="announcement-import-file-help">UTF-8 형식의 JSON 파일 하나를 선택해 주세요. 가져온 내용은 아래에서 수정할 수 있습니다.</p>
      {fileName ? <p role="status">{isReadingFile ? `파일 읽는 중… ${fileName}` : `선택한 파일: ${fileName}`}</p> : null}
      <label htmlFor="announcement-import-json">공고 JSON</label>
      <textarea
        id="announcement-import-json"
        disabled={isSubmitting}
        onChange={(event) => changeJson(event.currentTarget.value)}
        rows={16}
        spellCheck={false}
        value={jsonText}
      />
      <button
        className="registration-submit"
        disabled={isReadingFile || isValidating || isSubmitting || jsonText.trim().length === 0}
        onClick={() => void validateJson()}
        type="button"
      >
        {isValidating ? '검증 중…' : 'JSON 검증'}
      </button>

      {validation ? (
        <div aria-label="공고 JSON 검토 결과">
          <h3>검토 결과</h3>
          <p>스키마: {validation.schemaVersion}</p>
          <p>공고명: {summary.name ?? '확인할 수 없음'}</p>
          <p>
            공급행 {validation.supplyRows.length}건 · 일정 {summary.scheduleCount}건 · 첨부 {summary.attachmentCount}건
          </p>
          <ImportReviewDetails document={validatedDocument} rawJson={validatedJsonText} />
          <IssueList title="오류" issues={validation.errors} />
          <IssueList title="미확정값" issues={validation.unresolvedFields} />
          <IssueList title="경고" issues={validation.warnings} />
          {validation.supplyRows.map((row) => (
            <fieldset key={row.supplyRowIndex}>
              <legend>공급행 {row.supplyRowIndex + 1}: {row.sourceComplexName}</legend>
              <p>PNU: {row.pnu}</p>
              {row.candidates.length > 0 ? (
                <label>
                  연결 단지
                  <select
                    aria-label={`공급행 ${row.supplyRowIndex + 1} 연결 단지`}
                    disabled={isSubmitting}
                    onChange={(event) => {
                      const selectedId = event.currentTarget.value
                      setSelections((current) => {
                        const next = { ...current }
                        if (selectedId === '') {
                          delete next[row.supplyRowIndex]
                        } else {
                          next[row.supplyRowIndex] = Number(selectedId)
                        }
                        return next
                      })
                    }}
                    value={selections[row.supplyRowIndex] ?? ''}
                  >
                    <option value="">단지를 선택해 주세요</option>
                    {row.candidates.map((candidate) => (
                      <option key={candidate.housingComplexId} value={candidate.housingComplexId}>
                        {candidate.name} · {candidate.roadAddress} · {candidate.agencyCode}
                      </option>
                    ))}
                  </select>
                </label>
              ) : <p role="alert">일치하는 단지가 없어 등록할 수 없습니다.</p>}
            </fieldset>
          ))}
          <button
            className="registration-submit"
            disabled={!canSubmit || isSubmitting}
            onClick={() => void registerImport()}
            type="button"
          >
            {isSubmitting ? '등록 중…' : '검토한 내용으로 등록'}
          </button>
        </div>
      ) : null}

      {success ? <p className="registration-message registration-success" role="status">{success}</p> : null}
      {error ? <RegistrationError fieldErrors={{}} message={error} /> : null}
      {createdId ? <Link className="admin-primary" to={`/admin/announcements/${createdId}`}>등록한 공고 상세 보기 →</Link> : null}
    </section>
  )
}

function ImportReviewDetails({ document, rawJson }: { document: unknown; rawJson: string | null }) {
  if (!isRecord(document)) {
    return null
  }
  const source = recordOf(document.source)
  const announcement = recordOf(document.announcement)
  const receptionPlace = recordOf(document.receptionPlace)

  return (
    <div aria-label="등록할 공고 내용" className="announcement-import-review">
      <h4>공고와 접수 정보</h4>
      <ReviewFields fields={[
        ['공식 원문 URL', source.originalUrl],
        ['원천 공고번호', source.sourceDocumentId],
        ['공급 유형', announcement.rentalType],
        ['모집 유형', announcement.recruitmentType],
        ['공급 기관', announcement.agencyCode],
        ['게시일', announcement.postedDate],
        ['접수 시작일', announcement.applicationStartDate],
        ['접수 종료일', announcement.applicationEndDate],
        ['당첨자 발표일', announcement.winnerAnnouncementDate],
        ['접수처', receptionPlace.name],
        ['접수 방식', receptionPlace.method],
        ['접수처 주소', receptionPlace.address],
        ['접수처 연락처', receptionPlace.contact],
        ['접수처 URL', receptionPlace.url],
      ]} />
      {recordsOf(document.supplyRows).map((row, rowIndex) => (
        <section key={rowIndex}>
          <h4>공급행 {rowIndex + 1} 상세</h4>
          <ReviewFields fields={[
            ['원문 단지명', recordOf(row.complexReference).sourceComplexName],
            ['원문 주택형', row.sourceHousingTypeName],
            ['입주 예정 연월', row.expectedMoveInMonth],
            ['공급 구분', row.supplyCategory],
            ['공급세대수', row.totalSupplyHouseholdCount],
          ]} />
          {recordsOf(row.targets).map((target, targetIndex) => (
            <section key={targetIndex}>
              <h5>공급대상 {targetIndex + 1}</h5>
              <ReviewFields fields={[
                ['대상', target.target],
                ['순위', target.supplyRank],
                ['공급세대수', target.supplyHouseholdCount],
                ['예비자 수', target.reserveCount],
                ['임대보증금', target.rentalDeposit],
                ['월 임대료', target.monthlyRent],
                ['전환보증금', target.convertedDeposit],
                ['신청 조건', target.applicationCondition],
              ]} />
            </section>
          ))}
        </section>
      ))}
      {recordsOf(document.schedules).map((schedule, index) => (
        <section key={index}>
          <h4>일정 {index + 1}</h4>
          <ReviewFields fields={[
            ['일정 유형', schedule.scheduleType],
            ['일정명', schedule.name],
            ['시작', schedule.startAt],
            ['종료', schedule.endAt],
          ]} />
        </section>
      ))}
      {recordsOf(document.attachments).map((attachment, index) => (
        <section key={index}>
          <h4>첨부 {index + 1}</h4>
          <ReviewFields fields={[
            ['파일명', attachment.fileName],
            ['파일 유형', attachment.fileType],
            ['파일 URL', attachment.fileUrl],
          ]} />
        </section>
      ))}
      <details>
        <summary>등록할 원본 JSON 전체 보기</summary>
        <pre>{rawJson}</pre>
      </details>
    </div>
  )
}

function ReviewFields({ fields }: { fields: Array<[string, unknown]> }) {
  return (
    <dl>
      {fields.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{reviewValue(value)}</dd>
        </div>
      ))}
    </dl>
  )
}

function reviewValue(value: unknown): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) {
    return '원본 JSON에서 정확한 숫자를 확인해 주세요.'
  }
  if (typeof value === 'string' || typeof value === 'number') {
    return String(value)
  }
  return '미기재'
}

function recordOf(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {}
}

function recordsOf(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.filter(isRecord) : []
}

function IssueList({
  title,
  issues,
}: {
  title: string
  issues: Array<{ path: string; reason: string }>
}) {
  if (issues.length === 0) {
    return null
  }
  return (
    <section aria-label={title}>
      <h4>{title}</h4>
      <ul>
        {issues.map((issue) => <li key={`${issue.path}-${issue.reason}`}>{issue.path}: {issue.reason}</li>)}
      </ul>
    </section>
  )
}

function suggestedSelections(
  validation: AnnouncementImportValidationResponse,
): Readonly<Record<number, number>> {
  const selected: Record<number, number> = {}
  for (const row of validation.supplyRows) {
    if (row.suggestedHousingComplexId !== null) {
      selected[row.supplyRowIndex] = row.suggestedHousingComplexId
    }
  }
  return selected
}

function documentSummary(value: unknown): {
  name: string | null
  scheduleCount: number
  attachmentCount: number
} {
  if (!isRecord(value)) {
    return { name: null, scheduleCount: 0, attachmentCount: 0 }
  }
  const announcement = isRecord(value.announcement) ? value.announcement : null
  return {
    name: announcement && typeof announcement.name === 'string' ? announcement.name : null,
    scheduleCount: Array.isArray(value.schedules) ? value.schedules.length : 0,
    attachmentCount: Array.isArray(value.attachments) ? value.attachments.length : 0,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
