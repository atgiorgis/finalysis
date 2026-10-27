/**
 * The original statement file on the user's disk (docs/plans/phase-8.md §7). A web page can delete a file only through
 * the File System Access API, in Chromium browsers (Chrome, Edge), and only for a file it got a handle to: from its own
 * file picker or a drop. Elsewhere there is no handle, and the app says "Delete it yourself". Feature-checked at every
 * step, never by browser name.
 */

/** The parts of a file handle the app uses; `remove` and `requestPermission` exist only where deletion works. */
export interface OriginalFile {
  name: string
  getFile(): Promise<File>
  remove?: () => Promise<void>
  requestPermission?: (descriptor: { mode: 'readwrite' }) => Promise<'granted' | 'denied' | 'prompt'>
}

/** A chosen file, with the handle to its original when the browser gave one. */
export interface PickedFile {
  file: File
  handle: OriginalFile | null
}

type OpenFilePicker = (options: {
  multiple: boolean
  excludeAcceptAllOption: boolean
  types: { description: string; accept: Record<string, string[]> }[]
}) => Promise<OriginalFile[]>

function picker(): OpenFilePicker | null {
  const candidate = (window as unknown as { showOpenFilePicker?: OpenFilePicker }).showOpenFilePicker
  return typeof candidate === 'function' ? candidate.bind(window) : null
}

/** True where the app's own picker can hand out a file handle (Chromium). */
export function canPickWithHandle(): boolean {
  return picker() !== null
}

/** Opens the browser's picker for one statement file; null when the user cancels. */
export async function pickWithHandle(extensions: string[]): Promise<PickedFile | null> {
  const open = picker()
  if (!open) {
    return null
  }
  try {
    const [handle] = await open({
      multiple: false,
      excludeAcceptAllOption: false,
      types: [{ description: 'Statement files', accept: { 'application/octet-stream': extensions } }],
    })
    return handle ? { file: await handle.getFile(), handle } : null
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      return null
    }
    throw e
  }
}

/**
 * The handle behind a dropped file, where the browser offers one. Must be asked for during the drop event itself;
 * the promise may settle later.
 */
export function handleFromDrop(items: DataTransferItemList | undefined): Promise<OriginalFile | null> {
  const item = items && items.length === 1 ? items[0] : null
  const getHandle = (item as unknown as { getAsFileSystemHandle?: () => Promise<OriginalFile | null> } | null)
    ?.getAsFileSystemHandle
  if (!item || typeof getHandle !== 'function') {
    return Promise.resolve(null)
  }
  return getHandle.call(item).catch(() => null)
}

/** True when this original can be deleted by the app. */
export function canDelete(handle: OriginalFile | null): handle is OriginalFile & { remove: () => Promise<void> } {
  return !!handle && typeof handle.remove === 'function'
}

/** Deletes the original, permanently (not to the Trash), after the browser's own permission prompt. */
export async function deleteOriginal(handle: OriginalFile): Promise<'deleted' | 'denied' | 'unsupported'> {
  if (!canDelete(handle)) {
    return 'unsupported'
  }
  if (typeof handle.requestPermission === 'function') {
    const permission = await handle.requestPermission({ mode: 'readwrite' })
    if (permission !== 'granted') {
      return 'denied'
    }
  }
  await handle.remove()
  return 'deleted'
}
