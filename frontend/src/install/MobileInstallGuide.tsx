import { useState } from 'react'
import { Button } from '../design-system/components/Button'
import { NotificationDialog } from '../notifications/NotificationDialog'
import { useMobileInstall, type InstallPlatform } from './MobileInstallContext'
import styles from './MobileInstall.module.css'

const guides = {
  ios: [
    { title: 'Safari에서 공유를 눌러요', description: '더 보기(···) → 공유. 공유 버튼이 바로 보이면 눌러 주세요.', image: 'iphone-share.png', alt: 'iPhone Safari의 더 보기 메뉴에 있는 공유 버튼', position: 'iosShare', target: [550, 1628, 125, 65] },
    { title: '홈 화면에 추가를 선택해요', description: '공유 화면의 더 보기(···)를 열고 홈 화면에 추가를 눌러요.', image: 'iphone-home.png', alt: 'iPhone 공유 메뉴의 홈 화면에 추가 항목 실제 캡처', position: 'iosHome', target: [320, 1768, 245, 65] },
    { title: '추가를 누르면 끝!', description: '오른쪽 위 추가를 눌러요. 웹 앱으로 열기가 보이면 켜 두세요.', image: 'iphone-add.png', alt: 'iPhone 복덕방 홈 화면에 추가 확인창의 추가 버튼 실제 캡처', position: 'iosAdd', target: [1077, 330, 91, 78] },
  ],
  android: [
    { title: 'Chrome에서 더 보기를 눌러요', description: '주소창 오른쪽의 점 세 개(⋮)를 눌러 주세요.', image: 'android-browser.png', alt: 'Android Chrome 주소창 오른쪽의 더 보기 버튼 실제 캡처', position: 'androidBrowser', target: [1011, 150, 43, 53] },
    { title: '홈 화면에 추가를 선택해요', description: '메뉴 아래쪽에 있어요. 설치와 바로가기가 나오면 설치를 선택해요.', image: 'android-menu.png', alt: 'Android Chrome 메뉴의 홈 화면에 추가 항목 실제 캡처', position: 'androidMenu', target: [630, 1974, 270, 65] },
    { title: '설치를 누르면 끝!', description: '설치 또는 추가를 눌러요. 완료되면 홈 화면의 복덕방 아이콘으로 열어 주세요.', image: 'android-add.png', alt: 'Android Chrome 복덕방 앱 설치 확인창의 설치 버튼 실제 캡처', position: 'androidAdd', target: [880, 1455, 85, 60] },
  ],
} as const

export function MobileInstallGuide({ initialPlatform, onClose, notification = false, message = '' }: {
  readonly initialPlatform: InstallPlatform; readonly onClose: () => void; readonly notification?: boolean; readonly message?: string
}) {
  const platform = initialPlatform
  const install = useMobileInstall()
  const canInstall = platform === 'android' && install?.platform === 'android' && install.canPrompt
  const [step, setStep] = useState(0)
  const guide = guides[platform][step]
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState(false)
  const copy = async () => {
    try { await navigator.clipboard.writeText(window.location.href); setCopied(true); setCopyError(false) }
    catch { setCopyError(true) }
  }
  return <NotificationDialog title={notification ? 'iPhone에서 알림 받기' : '홈 화면에 복덕방 추가하기'} onClose={onClose}>
    {message && <p role="status">{message}</p>}
    {canInstall && <div className={styles.directInstall}>
      <Button className={styles.add} disabled={install.busy} onClick={install.open}>복덕방 설치하기</Button>
      <p>설치창에서 ‘설치’를 눌러 주세요.</p>
    </div>}
    <div className={styles.stepHeading} aria-live="polite"><span>{step + 1} / 3</span><h3>{guide.title}</h3><p>{guide.description}</p></div>
    <figure className={styles.capture}><div className={`${styles.viewport} ${styles[guide.position]}`}><div className={styles.shot}>
        <img src={`/install/${guide.image}`} alt={guide.alt} />
        <svg className={styles.touchMark} viewBox={platform === 'ios' ? '0 0 1206 2622' : '0 0 1080 2400'} aria-hidden="true" focusable="false">
          <ellipse cx={guide.target[0]} cy={guide.target[1]} rx={guide.target[2]} ry={guide.target[3]} vectorEffect="non-scaling-stroke" />
        </svg>
      </div></div>
      <figcaption>복덕방 · {platform === 'ios' ? 'iOS 26 Safari' : 'Android Chrome'} 실제 캡처<br />기기·버전에 따라 메뉴 위치가 달라요.</figcaption>
    </figure>
    <p className={styles.tip}>{platform === 'ios'
      ? '알림은 추가한 앱에서 로그인 후 ‘이 기기 알림 받기’를 켜 주세요. iOS 16.4 이상이 필요해요.'
      : '안드로이드는 설치하지 않아도 알림을 받을 수 있어요. 알림 설정에서 ‘이 기기 알림 받기’를 켜 주세요.'}</p>
    <div className={styles.stepActions}>
      {step > 0 && <Button onClick={() => setStep(step - 1)}>이전</Button>}
      <Button className={styles.next} onClick={step === 2 ? onClose : () => setStep(step + 1)}>{step === 2 ? '확인했어요' : '다음'}</Button>
    </div>
    <details className={styles.browserHelp}><summary>카카오톡·다른 앱 안에서 보고 있나요?</summary>
      <p>주소를 복사해 {platform === 'ios' ? 'Safari' : 'Chrome'}에서 열어 주세요.</p>
      <Button onClick={() => { void copy() }}>{copied ? '주소를 복사했어요' : '현재 주소 복사'}</Button>
      {copyError && <p role="status">주소를 길게 눌러 복사해 주세요.<br /><span className={styles.url}>{window.location.href}</span></p>}
    </details>
  </NotificationDialog>
}
