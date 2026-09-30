import { afterEach, describe, expect, it, vi } from 'vitest'
import { attachmentErrorMessage, loadAnnouncementAttachment } from './announcementAttachments.ts'

afterEach(() => vi.unstubAllGlobals())

describe('announcementAttachments API', () => {
  it('선택한 공고와 파일 ID로 PDF 본문만 요청한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('%PDF-1.7', { headers: { 'Content-Type': 'application/pdf' } }))
    vi.stubGlobal('fetch', fetcher)
    const blob = await loadAnnouncementAttachment('201', '601', false, new AbortController().signal)
    expect(blob.type).toBe('application/pdf')
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/201/attachments/601/content?download=false'), expect.any(Object))
  })

  it('성공 상태여도 PDF가 아닌 응답은 미리보기로 사용하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>error</html>', { headers: { 'Content-Type': 'text/html' } })))
    await expect(loadAnnouncementAttachment('201', '601', false, new AbortController().signal)).rejects.toThrow('ATTACHMENT_NOT_PDF')
  })

  it('빈 본문과 오류 응답은 파일로 내려받지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('')).mockResolvedValueOnce(new Response('{"code":"ATTACHMENT_TOO_LARGE"}', { status: 413 }))
    vi.stubGlobal('fetch', fetcher)
    await expect(loadAnnouncementAttachment('201', '601', true, new AbortController().signal)).rejects.toThrow('ATTACHMENT_UPSTREAM_FAILURE')
    try {
      await loadAnnouncementAttachment('201', '601', true, new AbortController().signal)
      expect.fail('expected error')
    } catch (error) {
      expect(attachmentErrorMessage(error)).toContain('파일이 커서')
    }
  })

  it('잘못된 ID는 네트워크 요청 전에 차단한다', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    await expect(loadAnnouncementAttachment('../1', '2', false, new AbortController().signal)).rejects.toThrow('ATTACHMENT_NOT_FOUND')
    expect(fetcher).not.toHaveBeenCalled()
  })
})
