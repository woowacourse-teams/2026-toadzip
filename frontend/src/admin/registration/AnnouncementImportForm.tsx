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
  const [validatedDocument, setValidatedDocument] = useState<unknown>(null)
  const [validation, setValidation] = useState<AnnouncementImportValidationResponse | null>(null)
  const [selections, setSelections] = useState<Readonly<Record<number, number>>>({})
  const [isValidating, setIsValidating] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const inputVersion = useRef(0)
  const summary = useMemo(() => documentSummary(validatedDocument), [validatedDocument])
  const canSubmit = !isValidating && validation?.registerable === true
    && validation.supplyRows.every((row) => selections[row.supplyRowIndex] !== undefined)

  function changeJson(value: string) {
    inputVersion.current += 1
    setJsonText(value)
    setValidatedDocument(null)
    setValidation(null)
    setSelections({})
    setIsValidating(false)
    setError(null)
    setSuccess(null)
  }

  async function validateJson() {
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
    setIsValidating(true)
    setError(null)
    setSuccess(null)
    try {
      const result = await validateAnnouncementImport(document)
      if (inputVersion.current !== validationInputVersion) {
        return
      }
      setValidatedDocument(document)
      setValidation(result)
      setSelections(suggestedSelections(result))
    } catch (requestError) {
      if (inputVersion.current !== validationInputVersion) {
        return
      }
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
    if (!validation || validatedDocument === null || !canSubmit) {
      return
    }
    setIsSubmitting(true)
    onSubmittingChange(true)
    setError(null)
    setSuccess(null)
    try {
      const created = await createAnnouncementImport(
        validatedDocument,
        validation.supplyRows.map((row) => ({
          supplyRowIndex: row.supplyRowIndex,
          housingComplexId: selections[row.supplyRowIndex],
        })),
      )
      setSuccess(
        `공고 #${created.announcementId}를 저장했습니다. 공급행 ${created.supplyRowCount}건, 일정 ${created.scheduleCount}건, 첨부 ${created.attachmentCount}건입니다.`,
      )
      setJsonText('')
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
      <p>Codex가 만든 v1 JSON을 붙여넣고 서버 검증 결과와 단지 연결을 확인한 뒤 등록합니다.</p>
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
        disabled={isValidating || isSubmitting || jsonText.trim().length === 0}
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
    </section>
  )
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
  return typeof value === 'object' && value !== null
}
