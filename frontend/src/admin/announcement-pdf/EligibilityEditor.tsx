import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { applyEligibility, FAMILY, FIELDS, moveToDirectCheck, NO_AGE_LIMIT, NO_COUNT_LIMIT, ruleText, type Applied, type Content, type Rule, type RuleSet } from './eligibilityDraftApi'
import {
  addCheck, addDirectItem, CHECK_PRESETS, editGuidanceItem, editProblems, fallbackIds, finishEdit, isManualCheck,
  removeGuidanceItem, removeManualCheck, renamePath, renameQualification, scopeChecks, scopeGuidances, updateRule,
} from './eligibilityEdit'
import styles from './EligibilityEditor.module.css'

/**
 * 신청자격 직접 수정. 신청 유형(탭) 이름, 조건 숫자·값, 조건 더하기·판정에서 빼기, 공고문에서 확인할 자격 문구를 고쳐
 * 저장하면 곧바로 사용자 화면에 반영한다(PDF 보정 적용과 같은 즉시 반영). 판정 규칙 검증은 서버가 하고, 거절하면 그 이유를 보인다.
 */
export function EligibilityEditor({ announcementId, ruleSet, onSaved, onCancel }: {
  announcementId: string; ruleSet: RuleSet; onSaved: (applied: Applied) => void; onCancel: (current: RuleSet) => void
}) {
  // 이어서 저장할 버전. 공개본을 복사한 뒤 저장이 거절되면 그 복사한 초안으로 바뀐다(공개본으로 다시 복사하면 충돌한다).
  const [base, setBase] = useState(ruleSet)
  const [content, setContent] = useState<Content>(ruleSet.content)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dirty = content !== base.content
  useUnsavedChanges(dirty)
  const problems = editProblems(content)

  async function save() {
    if (problems.length > 0) return
    setBusy(true); setError('')
    try {
      const applied = await applyEligibility(announcementId, base, finishEdit(content))
      if (applied.published || applied.saved) { onSaved(applied); return }
      // 저장하지 못했으면 편집을 닫지 않는다. 다른 곳에서 바뀐 신청자격을 불러왔으면 덮어쓰지 않게 그 내용으로 다시 보이고,
      // 서버가 거절했으면 고치던 내용을 남겨 이유를 보고 고치게 한다.
      if (JSON.stringify(applied.ruleSet.content) !== JSON.stringify(base.content)) setContent(applied.ruleSet.content)
      setBase(applied.ruleSet)
      setError(applied.reason)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '신청자격을 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  function cancel() {
    if (dirty && !window.confirm('고친 내용을 버릴까요?')) return
    // 저장이 거절돼 복사한 초안이 생겼으면 화면이 그 초안으로 이어 가게 넘긴다.
    onCancel(base)
  }

  return <section className={styles.editor} aria-label="신청자격 직접 수정">
    <header className={styles.header}>
      <div><h3>신청자격 직접 수정</h3><p className={styles.hint}>저장하면 사용자 화면에 곧바로 반영돼요. PDF 추출로 다시 반영하면 직접 고친 내용은 새 추출 결과로 덮여요.</p></div>
      <div className={styles.buttons}>
        <Button variant="secondary" disabled={busy} onClick={cancel}>취소</Button>
        <Button disabled={busy || !dirty || problems.length > 0} onClick={() => void save()}>{busy ? '저장하는 중…' : '저장하고 반영'}</Button>
      </div>
    </header>
    {problems.length > 0 && <div className={styles.problems} role="status"><strong>저장 전에 고칠 점</strong><ul>{problems.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    <Scope title="모든 신청자에게 적용" scopeId="common" content={content} onChange={setContent} disabled={busy} />
    {content.paths.map(path => <Scope key={path.id} title={path.label} scopeId={path.id} content={content} onChange={setContent} disabled={busy} />)}
  </section>
}

function Scope({ title, scopeId, content, onChange, disabled }: { title: string; scopeId: string; content: Content; onChange: (next: Content) => void; disabled: boolean }) {
  const path = content.paths.find(item => item.id === scopeId)
  const checks = scopeChecks(content, scopeId)
  const units = content.options.filter(option => option.pathId === scopeId)
    .map(option => content.supplyUnits.find(unit => unit.id === option.supplyUnitId)?.label ?? option.supplyUnitId)
  const qualifications = content.qualifications.filter(item => path?.qualificationIds.includes(item.id))
  const direct = scopeGuidances(content, scopeId, 'REQUIRED_QUALIFICATION')
  const fallbacks = fallbackIds(content)
  const listed = scopeGuidances(content, scopeId, 'REQUIRED_CHECK').filter(item => !fallbacks.has(item.id))
  const [preset, setPreset] = useState('')
  const [newItem, setNewItem] = useState('')
  const inputId = `eligibility-${scopeId}`

  return <fieldset className={styles.scope} disabled={disabled}>
    <legend>{path ? '신청 유형' : title}</legend>
    {path && <label className={styles.field} htmlFor={`${inputId}-label`}>탭 이름 (사용자에게 보이는 신청 유형 이름)
      <input id={`${inputId}-label`} value={path.label} maxLength={300} onChange={event => onChange(renamePath(content, path.id, event.target.value))} />
    </label>}
    {path && units.length > 0 && <p className={styles.hint}>신청 단위: {units.join(', ')}</p>}

    <h4>판정 조건</h4>
    {checks.length === 0 && <p className={styles.hint}>판정 조건이 없어요.</p>}
    {checks.length > 0 && <ul className={styles.checks}>{checks.map(check => <li key={check.id} className={styles.check}>
      <div className={styles.checkHead}>
        <strong>{ruleText(check.rule)}</strong>
        {isManualCheck(check)
          ? <Button variant="secondary" size="sm" onClick={() => onChange(removeManualCheck(content, scopeId, check.id))}>지우기</Button>
          : <Button variant="secondary" size="sm" onClick={() => onChange(moveToDirectCheck(content, scopeId, check.id))}>판정에서 빼기</Button>}
      </div>
      {!isManualCheck(check) && <p className={styles.hint}>원문: {check.label}</p>}
      <RuleFields rule={check.rule} id={`${inputId}-${check.id}`} onPatch={(at, patch) => onChange(updateRule(content, scopeId, check.id, at, patch))} at={[]} />
    </li>)}</ul>}
    <div className={styles.addRow}>
      <label className={styles.field} htmlFor={`${inputId}-preset`}>더할 조건
        <select id={`${inputId}-preset`} value={preset} onChange={event => setPreset(event.target.value)}>
          <option value="">더할 조건 고르기</option>
          {CHECK_PRESETS.map(item => <option key={item.key} value={item.key}>{item.name}</option>)}
        </select>
      </label>
      <Button variant="secondary" disabled={!preset} onClick={() => { onChange(addCheck(content, scopeId, preset)); setPreset('') }}>조건 더하기</Button>
    </div>

    {qualifications.length > 0 && <>
      <h4>해당하는지 공고문에서 확인할 자격</h4>
      {qualifications.map(item => <label key={item.id} className={styles.field} htmlFor={`${inputId}-${item.id}`}>자격 이름
        <input id={`${inputId}-${item.id}`} value={item.exactName} maxLength={300} onChange={event => onChange(renameQualification(content, item.id, event.target.value))} />
      </label>)}
    </>}

    <h4>공고문에서 확인할 자격 <span className={styles.hint}>(이 항목이 있으면 &lsquo;지원 가능&rsquo;으로 확정하지 않아요)</span></h4>
    <Items guidances={direct} content={content} onChange={onChange} label="공고문에서 확인할 자격" />
    <div className={styles.addRow}>
      <label className={styles.field} htmlFor={`${inputId}-new`}>더할 자격 문구
        <input id={`${inputId}-new`} value={newItem} maxLength={2000} placeholder="예: 주택청약종합저축 가입자" onChange={event => setNewItem(event.target.value)} />
      </label>
      <Button variant="secondary" disabled={!newItem.trim()} onClick={() => { onChange(addDirectItem(content, scopeId, newItem.trim())); setNewItem('') }}>자격 더하기</Button>
    </div>

    {listed.some(item => item.items.length > 0) && <>
      <h4>신청 전에 확인할 자격 <span className={styles.hint}>(판정에 넣지 않음)</span></h4>
      <Items guidances={listed} content={content} onChange={onChange} label="신청 전에 확인할 자격" />
    </>}
  </fieldset>
}

function Items({ guidances, content, onChange, label }: { guidances: Content['guidances']; content: Content; onChange: (next: Content) => void; label: string }) {
  const rows = guidances.flatMap(guidance => guidance.items.map((text, index) => ({ guidance, text, index })))
  if (rows.length === 0) return <p className={styles.hint}>없어요.</p>
  return <ul className={styles.items}>{rows.map(({ guidance, text, index }) => <li key={`${guidance.id}-${index}`}>
    <input aria-label={`${label} ${index + 1}`} value={text} maxLength={2000} onChange={event => onChange(editGuidanceItem(content, guidance.id, index, event.target.value))} />
    <Button variant="secondary" size="sm" onClick={() => onChange(removeGuidanceItem(content, guidance.id, index))}>지우기</Button>
  </li>)}</ul>
}

/** 비교 종류마다 고칠 수 있는 값만 보인다. 묶음(ALL·ANY)은 안의 비교를 차례로 보인다. 지역 목록은 이 화면에서 고치지 않는다. */
function RuleFields({ rule, id, at, onPatch }: { rule: Rule; id: string; at: number[]; onPatch: (at: number[], patch: Partial<Rule>) => void }) {
  const patch = (values: Partial<Rule>) => onPatch(at, values)
  const field = FIELDS[rule.field ?? ''] ?? '값'
  switch (rule.operator) {
    case 'ALL':
    case 'ANY':
      return <div className={styles.group}>
        <p className={styles.hint}>{rule.operator === 'ALL' ? '아래를 모두 충족' : '아래 중 하나 충족'}</p>
        {rule.children.map((child, index) => <RuleFields key={index} rule={child} id={`${id}-${index}`} at={[...at, index]} onPatch={onPatch} />)}
      </div>
    case 'AGE_RANGE':
      return rule.field === 'YOUNGEST_CHILD'
        ? <div className={styles.values}><NumberInput id={`${id}-max`} label="막내 자녀 최대 만 나이" unit="세" value={rule.maximum} onChange={maximum => patch({ maximum: maximum ?? Number.NaN })} /></div>
        : <div className={styles.values}>
          <NumberInput id={`${id}-min`} label="최소 만 나이" unit="세" value={rule.minimum} onChange={minimum => patch({ minimum: minimum ?? 0 })} />
          <NumberInput id={`${id}-max`} label="최대 만 나이" unit="세" placeholder="제한 없음" value={limited(rule.maximum, NO_AGE_LIMIT)} onChange={maximum => patch({ maximum: maximum ?? NO_AGE_LIMIT })} />
        </div>
    case 'NUMBER_RANGE':
      return rule.field === 'CURRENT_REGION_RESIDENCE_MONTHS'
        ? <div className={styles.values}><NumberInput id={`${id}-min`} label="최소 거주기간" unit="개월" value={rule.minimum} onChange={minimum => patch({ minimum: minimum ?? 0 })} /></div>
        : <div className={styles.values}>
          <NumberInput id={`${id}-min`} label={`${field} 최소`} unit="명" value={rule.minimum} onChange={minimum => patch({ minimum: minimum ?? 0 })} />
          <NumberInput id={`${id}-max`} label={`${field} 최대`} unit="명" placeholder="제한 없음" value={limited(rule.maximum, NO_COUNT_LIMIT)} onChange={maximum => patch({ maximum: maximum ?? NO_COUNT_LIMIT })} />
        </div>
    case 'MONTHS_SINCE':
      return <div className={styles.values}><NumberInput id={`${id}-max`} label="혼인 후 최대 기간" unit="개월" value={rule.maximum} onChange={maximum => patch({ maximum: maximum ?? Number.NaN })} /></div>
    case 'DAYS_FROM_REFERENCE':
      return <div className={styles.values}>
        <NumberInput id={`${id}-min`} label="기준일부터 최소" unit="일" value={rule.minimum} onChange={minimum => patch({ minimum: minimum ?? Number.NaN })} allowNegative />
        <NumberInput id={`${id}-max`} label="기준일부터 최대" unit="일" value={rule.maximum} onChange={maximum => patch({ maximum: maximum ?? Number.NaN })} allowNegative />
      </div>
    case 'MONEY_CEILING':
      return <div className={styles.values}>
        <NumberInput id={`${id}-max`} label={`${field} 상한`} unit="만원" step="any" value={rule.maximum === null || !Number.isFinite(rule.maximum) ? null : rule.maximum / 10_000}
          onChange={value => patch({ maximum: value === null ? Number.NaN : Math.round(value * 10_000) })} />
      </div>
    case 'INCOME_CEILING':
      return rule.incomeLimit ? <div className={styles.values}>
        <NumberInput id={`${id}-percent`} label={`${field} 기준표 비율`} unit="% 이하" value={Number.isFinite(rule.incomeLimit.percent) ? rule.incomeLimit.percent : null}
          onChange={percent => patch({ incomeLimit: { ...rule.incomeLimit!, percent: percent ?? Number.NaN } })} />
      </div> : null
    case 'EQUALS':
      return <div className={styles.values}><EqualsInput rule={rule} id={`${id}-expected`} label={field} onChange={expected => patch({ expected })} /></div>
    default:
      return null
  }
}

function limited(value: number | null, limit: number) {
  return value === null || value >= limit ? null : value
}

function EqualsInput({ rule, id, label, onChange }: { rule: Rule; id: string; label: string; onChange: (expected: string) => void }) {
  if (rule.field === 'FAMILY_TYPE') {
    return <label className={styles.field} htmlFor={id}>{label}
      <select id={id} value={rule.expected ?? ''} onChange={event => onChange(event.target.value)}>
        {Object.entries(FAMILY).map(([value, name]) => <option key={value} value={value}>{name}</option>)}
      </select>
    </label>
  }
  if (rule.expected === 'true' || rule.expected === 'false') {
    const owns = /OWNS/.test(rule.field ?? '')
    return <label className={styles.field} htmlFor={id}>{label}
      <select id={id} value={rule.expected} onChange={event => onChange(event.target.value)}>
        <option value="false">{owns ? '없어야 함' : '해당하지 않아야 함'}</option>
        <option value="true">{owns ? '있어야 함' : '해당해야 함'}</option>
      </select>
    </label>
  }
  return <NumberInput id={id} label={label} unit="" value={Number(rule.expected)} onChange={value => onChange(value === null ? '' : String(value))} />
}

/** 빈 칸은 null로 돌려준다. 호출하는 쪽이 빈 칸의 뜻(제한 없음·미입력)을 정한다. */
function NumberInput({ id, label, unit, value, onChange, placeholder, step = '1', allowNegative = false }: {
  id: string; label: string; unit: string; value: number | null; onChange: (value: number | null) => void; placeholder?: string; step?: string; allowNegative?: boolean
}) {
  // 단위는 label 밖에 두어 입력 이름이 '최대 만 나이세'처럼 붙어 읽히지 않게 한다.
  return <div className={styles.field}>
    <label htmlFor={id}>{label}</label>
    <span className={styles.number}>
      <input id={id} type="number" inputMode="decimal" min={allowNegative ? undefined : 0} step={step} placeholder={placeholder}
        value={value === null || !Number.isFinite(value) ? '' : value}
        onChange={event => onChange(event.target.value === '' ? null : Number(event.target.value))} />
      {unit && <span aria-hidden="true">{unit}</span>}
    </span>
  </div>
}

