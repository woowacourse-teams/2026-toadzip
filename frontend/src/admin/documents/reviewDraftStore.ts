import type { ManagementResource } from '../management/managementContract'

export type ReviewDraft = {
  key: string; resource: ManagementResource; id: number; selection: string; version: number;
  file: Blob; fileName: string; sha256: string; pages: string; sourceUrl: string; json: string; note: string; updatedAt: string
}
const databaseName = 'toadzip-admin-document-work'
const storeName = 'drafts'
function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!window.indexedDB) { reject(new Error('이 브라우저는 작업 보관을 지원하지 않습니다. 자료와 결과를 다운로드해 주세요.')); return }
    const request = window.indexedDB.open(databaseName, 1)
    request.onupgradeneeded = () => { request.result.createObjectStore(storeName, { keyPath: 'key' }) }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new Error('보관함을 열지 못했습니다. 브라우저 저장 권한을 확인해 주세요.'))
    request.onblocked = () => reject(new Error('다른 관리자 탭을 닫고 보관함을 다시 열어 주세요.'))
  })
}
export async function readReviewDraft(key: string): Promise<ReviewDraft | undefined> {
  const db = await database()
  try {
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction(storeName, 'readonly')
      const request = transaction.objectStore(storeName).get(key)
      request.onsuccess = () => resolve(request.result as ReviewDraft | undefined)
      request.onerror = () => reject(new Error('보관한 작업을 읽지 못했습니다.'))
    })
  } finally { db.close() }
}
export async function saveReviewDraft(draft: ReviewDraft): Promise<void> {
  if (draft.file.size > 25 * 1024 * 1024 || draft.json.length > 1000000 || draft.note.length > 2000) throw new Error('PDF 또는 수정안이 보관 한도를 초과했습니다.')
  const db = await database()
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(storeName, 'readwrite')
      const store = transaction.objectStore(storeName)
      const existing = store.getAll()
      existing.onsuccess = () => {
        const others = (existing.result as ReviewDraft[]).filter(item => item.key !== draft.key)
        if (others.length >= 5 || others.reduce((bytes, item) => bytes + item.file.size, draft.file.size) > 100 * 1024 * 1024) {
          transaction.abort(); reject(new Error('보관함은 최대 5개 작업·100MB입니다. 불필요한 보관 작업을 먼저 삭제해 주세요.')); return
        }
        store.put(draft)
      }
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(new Error('작업을 보관하지 못했습니다. 저장 공간을 확인해 주세요.'))
      transaction.onabort = () => reject(new Error('작업 보관을 중단했습니다. 저장 공간 또는 보관 개수를 확인해 주세요.'))
    })
  } finally { db.close() }
}
export async function removeReviewDraft(key: string): Promise<void> {
  const db = await database()
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(storeName, 'readwrite')
      transaction.objectStore(storeName).delete(key)
      transaction.oncomplete = () => resolve()
      transaction.onerror = () => reject(new Error('보관 작업을 삭제하지 못했습니다.'))
    })
  } finally { db.close() }
}
