import { ApiProblem } from '../api'

/** The backend's own minimum (PasswordPolicy.MIN_LENGTH); checked here only to answer before sending. */
export const MIN_PASSWORD_LENGTH = 12

/** The warning shown with the recovery key, word for word (docs/plans/phase-8.md §4.5). */
export const RECOVERY_WARNING =
  'Without this key and your password, your data is gone. Write it down and keep it somewhere safe.'

/** Characters as the backend counts them (code points), so an emoji counts once. */
export function passwordLength(password: string): number {
  return [...password].length
}

/** A problem for a new password typed twice: too short, or the two differ; null when it can be sent. */
export function newPasswordProblem(password: string, again: string): string | null {
  if (passwordLength(password) < MIN_PASSWORD_LENGTH) {
    return `The password needs at least ${MIN_PASSWORD_LENGTH} characters.`
  }
  if (password !== again) {
    return 'The two passwords are different.'
  }
  return null
}

/** The clock time a wait of `seconds` ends, like "10:42:30". */
export function timeAfter(seconds: number, now: Date = new Date()): string {
  return new Date(now.getTime() + seconds * 1000).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * What to say about a failed vault request, in plain words: the backend's sentence (its problems never quote a
 * password or a key), plus the time to try again after too many attempts.
 */
export function vaultErrorText(error: unknown): string {
  if (error instanceof ApiProblem) {
    if (error.slug === 'too-many-attempts' && error.retryAfter) {
      return `${error.message} You can try again at ${timeAfter(error.retryAfter)}.`
    }
    return error.message
  }
  return 'Finalysis didn’t answer. Check that the backend is running and try again.'
}

/** The 13 groups of a recovery key as shown once at setup. */
export function keyGroups(key: string): string[] {
  return key.split('-')
}
