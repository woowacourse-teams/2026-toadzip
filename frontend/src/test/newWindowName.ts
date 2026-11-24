// 새 창 링크는 보이는 글자 뒤에 보조기기용 '(새 창)'이 붙는다(shared/AdminIcon의 NewWindowMark).
// jsdom은 숨김 글자 앞 공백을 빼고 이름을 계산하므로 공백은 있어도 없어도 맞게 한다.
export const newWindowName = (text: string) => new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} ?\\(새 창\\)$`)
