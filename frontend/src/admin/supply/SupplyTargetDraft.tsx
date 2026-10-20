import type { CorrectionTarget } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

export function SupplyTargetDraft({ value, onChange, onRemove }: { value: CorrectionTarget; onChange: (value: CorrectionTarget) => void; onRemove: () => void }) {
  function field<K extends keyof CorrectionTarget>(key: K, next: CorrectionTarget[K]) { onChange({ ...value, [key]: next }) }
  return <fieldset className={styles.target}>
    <legend>{value.target || '새 공급대상'}{value.excluded ? ' · 제외 예정' : ''}</legend>
    <div className={styles.fields}>
      <label>공급대상<input required maxLength={255} value={value.target} onChange={event => field('target', event.target.value)} /></label>
      <label>공급순위<input maxLength={255} value={value.supplyRank ?? ''} onChange={event => field('supplyRank', event.target.value || null)} /></label>
      <label>대상 모집수 (세대)<input type="number" min="0" step="1" value={value.householdCount ?? ''} onChange={event => field('householdCount', event.target.value === '' ? null : Number(event.target.value))} /></label>
      <label>예비자수 (명)<input type="number" min="0" step="1" value={value.reserveCount ?? ''} onChange={event => field('reserveCount', event.target.value === '' ? null : Number(event.target.value))} /></label>
      {([['deposit', '보증금 (원)'], ['rent', '월 임대료 (원)'], ['convertedDeposit', '전환 보증금 (원)']] as const).map(([key, label]) =>
        <label key={key}>{label}<input inputMode="numeric" pattern="[0-9]*" maxLength={18} value={value[key] ?? ''} onChange={event => field(key, event.target.value || null)} /></label>)}
    </div>
    <label>신청조건<textarea maxLength={255} rows={2} value={value.condition ?? ''} onChange={event => field('condition', event.target.value || null)} /></label>
    <div className={styles.actions}>{value.id ? <button type="button" onClick={() => field('excluded', !value.excluded)}>{value.excluded ? '대상 복구' : '대상 제외'}</button>
      : <button type="button" onClick={onRemove}>추가한 대상 지우기</button>}
      {value.protectedValues && <span>관리자 보정 보호 중</span>}</div>
  </fieldset>
}
