import { Button } from '../../design-system/components/Button'
import styles from './AnnouncementDetailNavigation.module.css'

const content = [['info', '기본정보'], ['relations', '공급정보·단지 연결'], ['schedules', '접수 일정'], ['documents', '공고문·첨부파일'], ['history', '출처·수정 이력']]

export function AnnouncementDetailNavigation({ section, disabled, onSelect }: { section: string; disabled: boolean; onSelect: (section: string) => void }) {
  function tab([key, label]: string[]) {
    return <Button data-design-system key={key} data-admin-navigation={section !== key || undefined} disabled={disabled}
      aria-pressed={section === key} onClick={() => { if (section !== key) onSelect(key) }}>{label}</Button>
  }
  return <div className={styles.navigation}>
    <nav className={styles.content} aria-label="공고 상세 섹션">{content.map(tab)}</nav>
  </div>
}
