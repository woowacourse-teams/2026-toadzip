import styles from './HouseCharacterIcon.module.css'

/** 서비스 로고를 작은 아이콘으로 보여 준다. 헤더·로그인과 같은 로고 파일을 써서 모양과 색을 맞춘다. */
export function HouseCharacterIcon({ size = 24 }: { readonly size?: number }) {
  return <img className={styles.icon} src="/logo-bok-search.svg" alt="" width={size} height={size} />
}
