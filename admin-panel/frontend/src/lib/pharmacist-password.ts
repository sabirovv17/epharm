// Match the backend's shared BCrypt boundary. Never trim/truncate a credential.
export function isValidNewPharmacistPassword(password: string, minimumLength: number): boolean {
  return (
    password.length >= minimumLength &&
    password.length <= 128 &&
    Boolean(password.trim()) &&
    new TextEncoder().encode(password).length <= 72
  )
}
