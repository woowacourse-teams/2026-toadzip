import { useId, type InputHTMLAttributes, type SelectHTMLAttributes } from 'react'

type FieldErrors = Readonly<Record<string, string>>

type TextFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'name'> & {
  errors: FieldErrors
  label: string
  name: string
}

type SelectFieldProps = Omit<SelectHTMLAttributes<HTMLSelectElement>, 'name'> & {
  errors: FieldErrors
  label: string
  name: string
  options: ReadonlyArray<{ label: string; value: string }>
}

export function RegistrationTextField({
  errors,
  label,
  name,
  ...inputProps
}: TextFieldProps) {
  const error = errors[name]
  const inputId = useId()
  const errorId = `${inputId}-error`
  return (
    <div className="registration-field">
      <label htmlFor={inputId}>{label}</label>
      <input
        {...inputProps}
        aria-describedby={error ? errorId : undefined}
        aria-invalid={error ? true : undefined}
        id={inputId}
        name={name}
      />
      {error ? <span className="registration-field-error" id={errorId}>{error}</span> : null}
    </div>
  )
}

export function RegistrationSelectField({
  errors,
  label,
  name,
  options,
  ...selectProps
}: SelectFieldProps) {
  const error = errors[name]
  const inputId = useId()
  const errorId = `${inputId}-error`
  return (
    <div className="registration-field">
      <label htmlFor={inputId}>{label}</label>
      <select
        {...selectProps}
        aria-describedby={error ? errorId : undefined}
        aria-invalid={error ? true : undefined}
        id={inputId}
        name={name}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
      {error ? <span className="registration-field-error" id={errorId}>{error}</span> : null}
    </div>
  )
}

export function RegistrationError({
  fieldErrors,
  message,
}: {
  fieldErrors: FieldErrors
  message: string
}) {
  return (
    <div className="registration-message registration-error" role="alert">
      <p>{message}</p>
      {Object.keys(fieldErrors).length > 0 ? (
        <ul>
          {Object.entries(fieldErrors).map(([field, reason]) => (
            <li key={field}>{field}: {reason}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
