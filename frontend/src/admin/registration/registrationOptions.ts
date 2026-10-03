import { toRegistrationOptions } from './formValues'

export const rentalTypeOptions = toRegistrationOptions([
  'HAPPY_HOUSING',
  'NATIONAL_RENTAL',
  'PERMANENT_RENTAL',
  'PUBLIC_RENTAL_50Y',
  'INTEGRATED_PUBLIC_RENTAL',
  'REDEVELOPMENT_RENTAL',
  'ETC',
])
export const agencyOptions = toRegistrationOptions(['LH', 'SH', 'GH', 'ETC'])
