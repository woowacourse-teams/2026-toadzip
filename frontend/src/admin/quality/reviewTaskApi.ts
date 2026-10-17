import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const taskStates = { OPEN: '대기', IN_PROGRESS: '진행 중', ON_HOLD: '보류', DONE: '업무 완료' } as const
export type TaskState = keyof typeof taskStates
export type TaskResource = 'COMPLEX' | 'ANNOUNCEMENT'
export type ReviewTask = { resource: TaskResource; id: number; name: string; revision: number; assignee: string;
  dueDate: string | null; status: TaskState; note: string; actor: string; updatedAt: string; overdue: boolean; sourceChanged: boolean }
export type TaskPage = { items: ReviewTask[]; page: number; totalElements: number; totalPages: number; hasNext: boolean }
export type TaskInput = Pick<ReviewTask, 'assignee' | 'dueDate' | 'status' | 'note'> & { expectedRevision: number }
const root = '/api/admin/quality/tasks'
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function parse(value: unknown): ReviewTask {
  if (!isRecord(value) || !['COMPLEX', 'ANNOUNCEMENT'].includes(String(value.resource)) || !count(value.id) || value.id < 1
    || typeof value.name !== 'string' || !count(value.revision) || value.revision < 1 || typeof value.assignee !== 'string'
    || !(value.dueDate === null || typeof value.dueDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value.dueDate)
      && Number.isFinite(Date.parse(value.dueDate)) && new Date(value.dueDate).toISOString().slice(0, 10) === value.dueDate)
    || typeof value.status !== 'string' || !Object.hasOwn(taskStates, value.status) || typeof value.note !== 'string'
    || typeof value.actor !== 'string' || typeof value.updatedAt !== 'string' || !Number.isFinite(Date.parse(value.updatedAt))
    || typeof value.overdue !== 'boolean' || typeof value.sourceChanged !== 'boolean') throw new Error('검토 업무 응답을 확인해 주세요.')
  return value as ReviewTask
}
export async function loadReviewTask(resource: TaskResource, id: number, signal: AbortSignal): Promise<ReviewTask | null> {
  const result = await requestManagementApi(`${root}/${resource}/${id}`, 'GET', undefined, signal)
  if (result === null) return null
  const task = parse(result)
  if (task.resource !== resource || task.id !== id) throw new Error('다른 대상의 업무입니다.')
  return task
}
export async function saveReviewTask(resource: TaskResource, id: number, input: TaskInput, signal: AbortSignal): Promise<ReviewTask> {
  const task = parse(await requestManagementApi(`${root}/${resource}/${id}`, 'PUT', input, signal))
  if (task.resource !== resource || task.id !== id || task.revision !== input.expectedRevision + 1) throw new Error('업무 대상과 저장 버전을 확인해 주세요.')
  return task
}
export async function taskAssignees(signal: AbortSignal): Promise<string[]> {
  const result = await requestManagementApi(`${root}/assignees`, 'GET', undefined, signal)
  if (!Array.isArray(result) || !result.every(name => typeof name === 'string' && name.length <= 255)) throw new Error('담당자 목록 응답을 확인해 주세요.')
  return result as string[]
}
export async function reviewTaskPage(params: URLSearchParams, alerts: boolean, signal: AbortSignal): Promise<TaskPage> {
  const value = await requestManagementApi(`${root}${alerts ? '/alerts' : ''}?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !count(value.page) || !count(value.totalElements)
    || !count(value.totalPages) || typeof value.hasNext !== 'boolean') throw new Error('업무 목록 응답을 확인해 주세요.')
  return { items: value.items.map(parse), page: value.page, totalElements: value.totalElements, totalPages: value.totalPages, hasNext: value.hasNext }
}
