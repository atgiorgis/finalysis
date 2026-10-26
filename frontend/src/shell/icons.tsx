import type { ReactNode } from 'react'

/** Stroke icons from the approved mockup, drawn in the text colour; decoration only (the label names the control). */
function Icon({ size = 16, width = 2, children }: { size?: number; width?: number; children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

export const MenuIcon = () => (
  <Icon size={20} width={2.2}>
    <path d="M4 7h16M4 12h16M4 17h16" />
  </Icon>
)

export const CloseIcon = () => (
  <Icon size={18} width={2.2}>
    <path d="M6 6l12 12M18 6 6 18" />
  </Icon>
)

export const MoonIcon = () => (
  <Icon>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
  </Icon>
)

export const SunIcon = () => (
  <Icon>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Icon>
)

export const CheckIcon = () => (
  <Icon size={13} width={2.8}>
    <path d="m5 12 5 5L20 7" />
  </Icon>
)

/** A calculator: figures computed by Finalysis (step 7e). */
export const CalculatorIcon = () => (
  <Icon size={14}>
    <rect x="5" y="3" width="14" height="18" rx="2" />
    <path d="M8 7h8M8 12h2m2 0h2M8 16h2m2 0h2" />
  </Icon>
)

/** The logo: a rising line on the accent-to-income tile. */
export function LogoMark() {
  return (
    <span className="logo-mark" aria-hidden="true">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.5">
        <path d="M4 18 10 12l4 4 6-8" />
      </svg>
    </span>
  )
}

/** One icon per tab, by path (tabs.ts). */
export const TAB_ICONS: Record<string, () => ReactNode> = {
  '/overview': () => (
    <Icon>
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </Icon>
  ),
  '/transactions': () => (
    <Icon>
      <path d="M4 6h16M4 12h16M4 18h10" />
    </Icon>
  ),
  '/import': () => (
    <Icon>
      <path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
    </Icon>
  ),
  '/alerts': () => (
    <Icon>
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10 21h4" />
    </Icon>
  ),
  '/recurring': () => (
    <Icon>
      <path d="M21 12a9 9 0 1 1-3-6.7M21 3v6h-6" />
    </Icon>
  ),
  '/chat': () => (
    <Icon>
      <path d="M21 12a8 8 0 0 1-11.6 7.2L4 21l1.8-5.4A8 8 0 1 1 21 12Z" />
    </Icon>
  ),
  '/goals': () => (
    <Icon>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="4" />
    </Icon>
  ),
  '/settings': () => (
    <Icon>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2v3m0 14v3m10-10h-3M5 12H2m15.1-7.1-2.1 2.1M9 15l-2.1 2.1m0-10.2L9 9m6 6 2.1 2.1" />
    </Icon>
  ),
}
