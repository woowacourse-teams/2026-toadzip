import { useMyPageReturnState } from './mypageNavigation'
import { Link } from 'react-router'
import { MapReturnLink } from '../../MapReturnLink'
import styles from './MyPageReturn.module.css'

export function PageReturnLink() {
  const state = useMyPageReturnState()
  return state ? <Link className={styles.back} to={state.mypageReturn} aria-label="마이페이지로 돌아가기" title="마이페이지로 돌아가기">
    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <path d="m6 6 12 12M18 6 6 18" />
    </svg>
  </Link> : <MapReturnLink />
}
