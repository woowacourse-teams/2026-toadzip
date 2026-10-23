import styles from './LoginBrand.module.css'

export function LoginBrand({ descriptionId }: { readonly descriptionId?: string }) {
  return <div className={styles.brand}>
    <div className={styles.brandLockup}>
      <img src="/logo-bok-search.svg" alt="" width="52" height="52" />
      <p className={styles.brandName}>공공주택 복덕방</p>
    </div>
    <p id={descriptionId} className={styles.value}><strong>관심있는 공고의<br />새 소식을 받아보세요.</strong></p>
  </div>
}
