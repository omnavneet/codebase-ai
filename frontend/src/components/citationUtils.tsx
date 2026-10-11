/** Shape returned by the agent endpoints (investigate/explain/debug). */
export interface AgentInvestigation {
  answer: string;
  trace: string[];
  iterations: number;
  filesRead: string[];
  searchesPerformed: string[];
  truncated?: boolean;
}

export type CitationClickHandler = (
  filePath: string,
  startLine?: number,
  endLine?: number,
) => void;
