export interface Session {
  id: string
  title: string
  updatedAt: string
}

export interface Citation {
  file_path?: string
  start_line: number
  end_line: number
  content?: string
}

export interface Investigation {
  answer: string
  trace: string[]
  iterations: number
  filesRead: string[]
  searchesPerformed: string[]
  truncated?: boolean
}

export interface Message {
  id: string
  role: "user" | "assistant"
  content: string
  citations?: Citation[]
  createdAt: string
  /** Populated when the message came from a Deep investigate run. */
  investigation?: Investigation
  deep?: boolean
}

export type TabId = "chat" | "files" | "search" | "analyze"

export type AnalyzeSubMode = "explain" | "debug" | "review" | "docstrings"

