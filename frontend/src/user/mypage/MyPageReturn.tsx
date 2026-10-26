import { useMyPageReturnState } from './mypageNavigation'
import { Link } from 'react-router'
import { MapReturnLink } from '../../MapReturnLink'
import styles from './MyPageReturn.module.css'

export function PageReturnLink() {
  const state = useMyPageReturnState()
  return state ? <Link className={styles.back} to={state.mypageReturn}>마이페이지로 돌아가기</Link> : <MapReturnLink />
}
