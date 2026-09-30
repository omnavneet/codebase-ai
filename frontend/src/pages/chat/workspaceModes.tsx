import type { ReactNode } from 'react'
import type { TabId } from './types'

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
  agent: {
    title: 'Agent',
    description:
      'Run a multi-step investigation and follow every tool call it makes.',
    icon: icon(<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />),
  },
  docs: {
    title: 'Documentation',
    description:
      'Generate documentation comments for one file, or a README for the project.',
    icon: icon(
      <>
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <line x1="16" y1="13" x2="8" y2="13" />
        <line x1="16" y1="17" x2="8" y2="17" />
      </>,
    ),
  },
  explain: {
    title: 'Explain code',
    description:
      'Get a walkthrough of a file or a single symbol, grounded in the surrounding code.',
    icon: icon(
      <>
        <circle cx="12" cy="12" r="10" />
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </>,
    ),
  },
  debug: {
    title: 'Debug',
    description:
      'Describe the failure and paste a stack trace to locate the likely cause.',
    icon: icon(
      <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />,
    ),
  },
  improve: {
    title: 'Code review',
    description:
      'Review a file for bugs, performance, security and readability, with concrete fixes.',
    icon: icon(
      <>
        <polyline points="9 11 12 14 22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </>,
    ),
  },
}
