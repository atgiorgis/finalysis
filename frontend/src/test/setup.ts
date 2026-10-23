import '@testing-library/jest-dom/vitest'

import { configure } from '@testing-library/react'

// The suite runs its files in parallel; under that load a read can take longer than the default 1 s to show.
configure({ asyncUtilTimeout: 5000 })
