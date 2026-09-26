import { RegistrationSelectField, RegistrationTextField } from '../registration/RegistrationFields'
import { type Values } from './api'
import { type Field, valueAt, labels } from './fields'
export function EditFields({ fields, data, errors, scheduleReviewed = false, onValueChange }: {
  fields: Field[]; data: Values; errors: Record<string,string>; scheduleReviewed?: boolean; onValueChange?: (name: string, value: string) => void
}) {
  return <div className="registration-grid">{fields.map(field => {
    const raw = valueAt(data, field.name)
    const value = raw === null || raw === undefined || typeof raw === 'object' ? '' : String(raw)
    const binding = onValueChange ? {value,onChange:(event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => onValueChange(field.name,event.target.value)} : {defaultValue:value}
    const readonly = scheduleReviewed && ['applicationStartDate','applicationEndDate'].includes(field.name)
    if (field.options) {
      const options = field.options.includes(value) || !value ? field.options : [value, ...field.options]
      return <RegistrationSelectField key={field.name} label={field.label} name={field.name} required={field.required}
        errors={errors} {...binding} options={[{value:'',label:field.required ? '선택해 주세요' : '미확인'},...options.map(option => ({value:option,label:labels[option] ?? option}))]} />
    }
    return <RegistrationTextField key={field.name} label={field.label} name={field.name} errors={errors}
      {...binding} type={field.type ?? 'text'} required={field.required} min={field.min} max={field.max} step={field.step}
      maxLength={field.type ? undefined : 255} readOnly={readonly} />
  })}</div>
}
