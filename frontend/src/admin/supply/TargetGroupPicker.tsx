import { groupsText, targetGroupLabels, targetGroups, toggledGroup, type TargetGroup } from './targetGroups'
import styles from './TargetGroupPicker.module.css'

/** 공급대상 분류 여러 개 고르기. 닫혀 있을 때도 고른 분류가 보인다. name은 화면 낭독용 이름("공급대상 1")이다. */
export function TargetGroupPicker({ name, groups, onChange, note }: { name: string; groups: TargetGroup[]; onChange: (groups: TargetGroup[]) => void; note?: string }) {
  return <details className={styles.picker}>
    <summary aria-label={`${name} 분류: ${groupsText(groups)}`}>{groupsText(groups)}{note && <small>{note}</small>}</summary>
    <fieldset className={styles.options}><legend className="visually-hidden">{name} 분류</legend>
      {targetGroups.map(group => <label key={group}><input type="checkbox" checked={groups.includes(group)}
        onChange={event => onChange(toggledGroup(groups, group, event.target.checked))} />{targetGroupLabels[group]}</label>)}
    </fieldset>
  </details>
}
