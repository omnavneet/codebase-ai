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

export interface Message {
  id: string
  role: "user" | "assistant"
  content: string
  citations?: Citation[]
  createdAt: string
}

export type TabId =
  | "chat"
  | "files"
  | "search"
  | "agent"
  | "docs"
  | "explain"
  | "debug"
  | "improve"
