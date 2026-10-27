import { useEffect, useState, type FormEvent } from 'react'
import { access, type AccessStatus } from '../api'
import { hasVault, useVault } from './VaultContext'
import { vaultErrorText } from './vaultText'

const REASONS: Record<string, string> = {
  'address-gone': 'This computer isn’t on that address now (another network?). Choose its current address.',
  'too-many-failures': 'Closed after too many wrong passwords from phones. It opens again the next time you unlock here.',
  'listener-failed': 'It didn’t start. Another program may be using the port; try another one.',
  locked: 'It opens when the vault is unlocked.',
  'no-server': 'It opens once the backend has started.',
}

/**
 * Settings → Phone access (docs/plans/phase-8.md §9): off by default; on, a phone on this network reaches Finalysis over
 * HTTPS at one of this computer's private addresses, after logging in with the vault's password. The phone first
 * installs the app's own CA (the file below, its fingerprint compared on the phone). Set on the computer only.
 */
export default function PhoneAccessSettings() {
  const { status: vault } = useVault()
  const [lan, setLan] = useState<boolean | null>(null)
  const [state, setState] = useState<AccessStatus | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [address, setAddress] = useState('')
  const [port, setPort] = useState('8443')
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!hasVault(vault)) {
      return
    }
    access
      .session()
      .then(async (session) => {
        setLan(session.lan)
        if (!session.lan) {
          show(await access.status())
        }
      })
      .catch((e: Error) => setMessage({ ok: false, text: e.message }))
    // Read once, when the card opens.
  }, [])

  function show(read: AccessStatus) {
    setState(read)
    setEnabled(read.enabled)
    setAddress(read.address ?? read.availableAddresses[0] ?? '')
    setPort(String(read.port || 8443))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    const portNumber = Number(port)
    if (!Number.isInteger(portNumber) || portNumber < 1024 || portNumber > 65535) {
      setMessage({ ok: false, text: 'Choose a port from 1024 to 65535.' })
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      const saved = await access.save({ enabled, address: address || null, port: portNumber })
      show(saved)
      setMessage({ ok: true, text: saved.open ? 'Phone access is on.' : saved.enabled ? 'Saved.' : 'Phone access is off.' })
    } catch (err) {
      setMessage({ ok: false, text: vaultErrorText(err) })
    }
    setBusy(false)
  }

  if (!hasVault(vault)) {
    return null
  }
  if (lan) {
    return (
      <section className="card" aria-labelledby="settings-phone">
        <h2 id="settings-phone">Phone access</h2>
        <p className="muted">You’re on the phone now. Phone access is turned on and off on the computer.</p>
      </section>
    )
  }
  return (
    <section className="card" aria-labelledby="settings-phone">
      <h2 id="settings-phone">Phone access</h2>
      <p className="muted">
        Lets a phone on this network open Finalysis over HTTPS, after logging in with your password. Off by default.
      </p>
      {state && (
        <form className="settings-form" noValidate onSubmit={(e) => void submit(e)}>
          <label className="settings-check">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            Allow phones on this network
          </label>
          <label>
            This computer’s address
            <select value={address} onChange={(e) => setAddress(e.target.value)} disabled={!enabled}>
              {state.availableAddresses.length === 0 && <option value="">No home-network address found</option>}
              {state.availableAddresses.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </label>
          <label>
            Port
            <input
              type="number"
              inputMode="numeric"
              min={1024}
              max={65535}
              value={port}
              onChange={(e) => setPort(e.target.value)}
              disabled={!enabled}
            />
          </label>
          {message && (
            <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'error'}>
              {message.text}
            </p>
          )}
          <button type="submit" className="button-secondary" disabled={busy}>
            Save
          </button>
        </form>
      )}
      {state?.enabled && !state.open && state.reason && <p className="vault-notice">{REASONS[state.reason] ?? state.reason}</p>}
      {state?.open && state.url && (
        <div className="phone-access-steps">
          <p>
            On the phone, open <strong className="phone-url">{state.url}</strong> after installing this computer’s
            certificate once:
          </p>
          <ol>
            <li>
              <a href={access.caUrl} download="finalysis-local-ca.cer" className="button-secondary phone-ca-link">
                Save the certificate
              </a>{' '}
              and send it to the phone (AirDrop, or a message to yourself).
            </li>
            <li>
              Check that the phone shows this fingerprint (SHA-256):
              <code className="phone-fingerprint">{state.caFingerprint}</code>
            </li>
            <li>
              iPhone: install the profile (Settings → General → VPN &amp; Device Management), then turn on full trust
              (Settings → General → About → Certificate Trust Settings). Android: Settings → Security → Install a
              certificate → CA certificate.
            </li>
          </ol>
          <p className="muted">
            The certificate can only vouch for addresses on your home network. Turning phone access off, or locking,
            closes it; logged-in phones are logged out.
          </p>
        </div>
      )}
    </section>
  )
}
