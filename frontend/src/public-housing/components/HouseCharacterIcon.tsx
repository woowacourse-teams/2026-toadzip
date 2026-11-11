import styles from './HouseCharacterIcon.module.css'

/** 서비스 로고(웃는 집 + 돋보기 손잡이)를 작게 줄인 그림. 작은 크기에서 보이도록 얼굴 선만 굵게 했다. */
export function HouseCharacterIcon({ size = 24 }: { readonly size?: number }) {
  return <svg className={styles.icon} width={size} height={size} viewBox="0 0 164 164" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <g transform="translate(6 -6)">
      <path d="M117.56 117.56 140 140" stroke="currentColor" strokeWidth="20" />
      <path d="M20 76.6A18 18 0 0 1 26.69 62.6L60.69 35.14A18 18 0 0 1 83.31 35.14L117.31 62.6A18 18 0 0 1 124 76.6V100A24 24 0 0 1 100 124H44A24 24 0 0 1 20 100Z" stroke="currentColor" strokeWidth="20" />
      <path className={styles.face} d="M58 77V89M86 77V89" strokeWidth="11" />
      <path className={styles.face} d="M61 100C66 106 78 106 83 100" strokeWidth="9" />
    </g>
  </svg>
}
