import { useState, useSyncExternalStore } from 'react'
import { Button } from '../design-system/components/Button'
import { useNotificationPush } from './NotificationPushContext'
import { pushUiEnabled } from './firebasePushSdk'
import { pushInstallationHelp } from './pushInstallationHelp'
import { NotificationDialog } from './NotificationDialog'
import type { PushClient } from './firebasePushClient'
import styles from './NotificationPushControl.module.css'

export function NotificationPushControl() {
  const context = useNotificationPush()
  const [helpOpen, setHelpOpen] = useState(false)
  const help = pushInstallationHelp()
  if (!pushUiEnabled) return null
  return <section className={styles.control} aria-label="이 기기 푸시 설정">
    <div className={styles.heading}><h3>기기 알림</h3><span>이 기기에만 적용</span></div>
    <p className={styles.description}>관심 단지·지역의 새 공고를 화면 밖에서도 받아요.</p>
    {help ? <Button className={styles.primary} onClick={() => setHelpOpen(true)}>이 기기 알림 받기</Button>
      : context?.error ? <><p className={styles.message} role="alert">{context.error}</p><Button onClick={context.retry}>다시 시도</Button></>
      : context?.client ? <DeviceControl client={context.client} retry={context.retry} />
        : <p className={styles.message} role="status">기기 설정을 확인하고 있어요…</p>}
    <p className={styles.note}>직접 로그아웃하면 잠시 중지되고, 다시 로그인하면 복원돼요.</p>
    {helpOpen && <NotificationDialog title={help === 'ios-update' ? 'iOS 업데이트가 필요해요' : 'iPhone에서 알림 받기'} onClose={() => setHelpOpen(false)}>
      {help === 'ios-update' ? <p>기기 알림은 iOS·iPadOS 16.4 이상에서 사용할 수 있어요. 설정 → 일반 → 소프트웨어 업데이트에서 확인해 주세요.</p> : <>
        <p>홈 화면에 복덕방을 추가하면 새 공고 알림을 받을 수 있어요.</p>
        <ol className={styles.steps}>
          <li><strong>Safari에서 복덕방 열기</strong><span>다른 앱 안에서 보고 있다면 주소를 복사해 Safari에서 열어 주세요.</span></li>
          <li><strong>공유 버튼 누르기</strong><span>화살표가 위로 향한 공유 아이콘을 눌러 주세요. 메뉴 안에 있을 수도 있어요.</span></li>
          <li><strong>‘홈 화면에 추가’ 선택</strong><span>목록을 내려 찾고 ‘추가’를 눌러 주세요. ‘웹 앱으로 열기’가 보이면 켜 주세요.</span></li>
          <li><strong>홈 화면의 복덕방에서 알림 켜기</strong><span>추가된 아이콘으로 열고 로그인한 뒤, 알림 설정에서 ‘이 기기 알림 받기’ → ‘허용’을 눌러 주세요.</span></li>
        </ol>
        <p className={styles.note}>iOS·iPadOS 16.4 이상이 필요해요.</p>
      </>}
      <Button className={styles.primary} onClick={() => setHelpOpen(false)}>확인했어요</Button>
    </NotificationDialog>}
  </section>
}
function DeviceControl({ client, retry }: { readonly client: PushClient; readonly retry: () => void }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot)
  const working = state.status === 'loading' || state.status === 'busy'
  return <>
    {state.status === 'on' && <div className={styles.enabled}><span aria-hidden="true">✓</span> 알림 켜짐</div>}
    {state.message && <p className={styles.message} role={state.status === 'error' ? 'alert' : 'status'}>{state.message}</p>}
    {state.status === 'error' ? <Button onClick={retry}>다시 시도</Button>
      : state.status === 'on' ? <button className={styles.secondary} type="button" onClick={() => void client.disable()}>알림 끄기</button>
        : <Button className={styles.primary} disabled={working} onClick={() => void client.enable()}>
          {working ? '알림 설정 중…' : '이 기기 알림 받기'}
        </Button>}
  </>
}
