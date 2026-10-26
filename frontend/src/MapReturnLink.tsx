import { Link } from 'react-router'
import { lastExplorerPath } from './user/auth/loginReturn'
import styles from './MapReturnLink.module.css'

export function MapReturnLink() {
  return <Link className={styles.link} to={lastExplorerPath()}>지도로 이동</Link>
}
