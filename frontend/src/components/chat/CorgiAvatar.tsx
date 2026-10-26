import { AI_NAME } from '../../corgi'

/**
 * Corgi's round avatar: the name's initial on the accent. Decorative, since the name is always shown beside it; an
 * image can replace the initial here later.
 */
export default function CorgiAvatar({ size = 'small' }: { size?: 'small' | 'large' }) {
  return (
    <span className={`corgi-avatar corgi-avatar-${size}`} aria-hidden="true">
      {AI_NAME.charAt(0)}
    </span>
  )
}
