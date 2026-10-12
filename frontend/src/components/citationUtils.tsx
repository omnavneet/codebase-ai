/** Shape returned by the agent endpoints (investigate/explain/debug). */
export interface AgentInvestigation {
  answer: string;
  trace: string[];
  iterations: number;
  tool_calls: number;
  files_read: string[];
  searches_performed: string[];
  truncated?: boolean;
  stopped_early?: boolean;
  stop_reason?: string;
  usage?: {
    input_tokens: number;
    output_tokens: number;
    total_tokens: number;
  };
  findings?: Array<{
    severity: string;
    category: string;
    title: string;
    lines: string;
    description: string;
    suggestion: string;
    code_before?: string | null;
    code_after?: string | null;
  }>;
  frames?: Array<{
    file?: string | null;
    line?: number | null;
    symbol?: string | null;
    language?: string | null;
    raw?: string | null;
  }>;
  project_frames?: Array<{
    file?: string | null;
    line?: number | null;
    symbol?: string | null;
    language?: string | null;
    raw?: string | null;
  }>;
  stack_trace_language?: string | null;
  stack_trace_parsed?: boolean;
  stack_trace_notes?: string[];
}

export type CitationClickHandler = (
  filePath: string,
  startLine?: number,
  endLine?: number,
) => void;
