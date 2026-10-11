import type { ReactNode } from 'react'
import type { AnalyzeSubMode, TabId } from './types'

export interface WorkspaceModeMeta {
  title: string
  description: string
  icon: ReactNode
}

const icon = (children: ReactNode) => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)

/**
 * Heading and icon for each non-chat mode. Kept out of ChatPage so the copy
 * that explains each mode lives in one place.
 */
export const WORKSPACE_MODES: Record<Exclude<TabId, 'chat'>, WorkspaceModeMeta> = {
  files: {
    title: 'Files',
    description:
      'Browse the extracted source and read any file without leaving this project.',
    icon: icon(
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />,
    ),
  },
  search: {
    title: 'Semantic search',
    description:
      'Find code by what it does rather than by the words it happens to use.',
    icon: icon(
      <>
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </>,
    ),
  },
  analyze: {
    title: 'Analyze',
    description:
      'Explain code, debug failures, review a file, or generate docstrings.',
    icon: icon(
      <>
        <circle cx="12" cy="12" r="10" />
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </>,
    ),
  },
}

export interface AnalyzeSubModeMeta {
  title: string
  description: string
}

/** Labels for the four Analyze sub-modes. Backend paths are unchanged. */
export const ANALYZE_SUB_MODES: Record<AnalyzeSubMode, AnalyzeSubModeMeta> = {
  explain: {
    title: 'Explain',
    description: 'Walk through a file or a single symbol.',
  },
  debug: {
    title: 'Debug',
    description: 'Describe the failure and paste a stack trace.',
  },
  review: {
    title: 'Review',
    description: 'Review a file for bugs, performance, security and readability.',
  },
  docstrings: {
    title: 'Docstrings',
    description: 'Generate documentation comments for one file.',
  },
}

