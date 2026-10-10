import { requestManagementApi } from './api'
import { parseComplexVerification, type ComplexReviewInput, type ComplexVerification } from './complexVerificationContract'

const root = (id: string) => `/api/admin/housing-complexes/${id}/verification`
export async function getComplexVerification(id: string, signal?: AbortSignal): Promise<ComplexVerification> {
  return parseComplexVerification(await requestManagementApi(root(id), 'GET', undefined, signal))
}
export async function saveComplexReview(id: string, body: ComplexReviewInput, signal?: AbortSignal): Promise<ComplexVerification> {
  return parseComplexVerification(await requestManagementApi(`${root(id)}/reviews`, 'POST', body, signal))
}
