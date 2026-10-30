import { useId } from 'react'
import { AI_NAME } from '../../corgi'

/**
 * Corgi's face, wherever Corgi is named (the chat header, each answer, the categorizer's attribution, Settings): an
 * original drawing, a happy corgi front-on with pointed ears, a white blaze and muzzle and an open grin, on a round
 * background with a ring. Flat fills, readable from 24 to 40 px. The fur colours are fixed tokens (`--corgi-*`, in
 * theme.css beside the path tokens); only the background and the ring follow the theme.
 *
 * When an image is placed in `frontend/public/` as `corgi-avatar.png`, `.jpg` or `.svg`, that image is shown instead,
 * cropped to a circle (vite.config.ts finds it when Vite starts). The name is always shown beside the avatar: it never
 * stands in for the name.
 *
 * @param size its width and height in px (24 beside an attribution, 32 beside an answer, 40 in the chat header)
 * @param src  an image to show instead of the drawing; defaults to the one in `public/`, if any
 */
export default function CorgiAvatar({ size = 32, src = __CORGI_AVATAR__ }: { size?: number; src?: string | null }) {
  // One clip per avatar: several share a page, and an SVG id is the document's.
  const clip = `corgi-clip-${useId().replace(/:/g, '')}`
  if (src) {
    return (
      <img
        className="corgi-avatar corgi-avatar-image"
        src={src}
        alt={AI_NAME}
        width={size}
        height={size}
        style={{ width: size, height: size }}
      />
    )
  }
  return (
    <svg className="corgi-avatar" role="img" aria-label={AI_NAME} width={size} height={size} viewBox="0 0 64 64">
      <clipPath id={clip}>
        <circle cx="32" cy="32" r="31" />
      </clipPath>
      <circle className="corgi-bg" cx="32" cy="32" r="31" />
      {/* The face, drawn a little larger than its box, kept inside the circle (the clip isn't scaled with it). */}
      <g clipPath={`url(#${clip})`}>
        <g transform="translate(32 35) scale(1.1) translate(-32 -35)">
          {/* ears: pointed, with their inner colour */}
          <path className="corgi-fur" d="M11 33 L12 10 Q13.5 7 16.5 9 L29 20 Z" />
          <path className="corgi-fur" d="M53 33 L52 10 Q50.5 7 47.5 9 L35 20 Z" />
          <path className="corgi-ear-inner" d="M15.5 26 L15 13.5 L25 21.5 Z" />
          <path className="corgi-ear-inner" d="M48.5 26 L49 13.5 L39 21.5 Z" />
          {/* the head, its darker crown, and the white blaze and muzzle */}
          <ellipse className="corgi-fur" cx="32" cy="37" rx="21" ry="18" />
          <path className="corgi-fur-shade" d="M18 26 Q32 15 46 26 Q39 22 32 22 Q25 22 18 26 Z" />
          <path className="corgi-white" d="M29.5 21 Q32 18.5 34.5 21 L37 34 L27 34 Z" />
          <ellipse className="corgi-white" cx="32" cy="45" rx="15" ry="10" />
          {/* eyes, bright, with a highlight each */}
          <ellipse className="corgi-ink" cx="23.5" cy="33" rx="3.2" ry="3.6" />
          <ellipse className="corgi-ink" cx="40.5" cy="33" rx="3.2" ry="3.6" />
          <circle className="corgi-white" cx="24.7" cy="31.7" r="1.2" />
          <circle className="corgi-white" cx="41.7" cy="31.7" r="1.2" />
          {/* cheeks */}
          <ellipse className="corgi-blush" cx="19" cy="42" rx="3" ry="2" />
          <ellipse className="corgi-blush" cx="45" cy="42" rx="3" ry="2" />
          {/* nose, and the open grin with its tongue */}
          <ellipse className="corgi-ink" cx="32" cy="39.5" rx="4" ry="2.8" />
          <path className="corgi-ink" d="M25 44.5 Q32 43 39 44.5 Q36.5 52 32 52 Q27.5 52 25 44.5 Z" />
          <path className="corgi-tongue" d="M28.6 48.6 Q32 47.4 35.4 48.6 Q34.4 51.6 32 51.6 Q29.6 51.6 28.6 48.6 Z" />
        </g>
      </g>
      <circle className="corgi-ring" cx="32" cy="32" r="31" />
    </svg>
  )
}
