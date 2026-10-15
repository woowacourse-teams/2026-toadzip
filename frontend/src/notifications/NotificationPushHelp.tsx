import { Button } from '../design-system/components/Button'
import { NotificationDialog } from './NotificationDialog'
import { pushInstallationHelp } from './pushInstallationHelp'
import styles from './NotificationPushControl.module.css'
export function NotificationPushHelp({ onClose }: { readonly onClose: () => void }) {
  const help = pushInstallationHelp()
  if (!help) return <NotificationDialog title="브라우저에서 알림을 허용해 주세요" onClose={onClose}>
    <p>이 브라우저에서 복덕방 알림이 차단되어 있어요. 권한을 다시 요청할 수 없어 직접 변경해야 해요.</p>
    <ol className={styles.steps}>
      <li><strong>사이트의 알림 권한 열기</strong><span>브라우저 설정의 사이트 권한에서 복덕방 주소를 찾아 주세요.</span></li>
      <li><strong>알림을 ‘허용’으로 변경</strong><span>기기 설정에서도 사용 중인 브라우저의 알림이 켜져 있는지 확인해 주세요.</span></li>
      <li><strong>복덕방을 새로고침한 뒤 알림 켜기</strong><span>알림 설정에서 ‘이 기기 알림 받기’를 켜 주세요.</span></li>
    </ol>
    <Button className={styles.primary} onClick={onClose}>확인했어요</Button>
  </NotificationDialog>
  return <NotificationDialog title={help === 'ios-update' ? 'iOS 업데이트가 필요해요' : 'iPhone에서 알림 받기'} onClose={onClose}>
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
    <Button className={styles.primary} onClick={onClose}>확인했어요</Button>
  </NotificationDialog>
}
