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

/** A citation that matched a real file in the project's file list. */
export interface ResolvedCitation {
  /** The project path to open — the first match at the winning tier. */
  path: string;
  /** Other files the citation could mean; surfaced in the link tooltip. */
  others: string[];
}

/** Looks a cited path up in the project; null means "not a real file". */
export type CitationResolver = (citedPath: string) => ResolvedCitation | null;

/**
 * Precompute citation resolution over the file list: exact path first, then
 * files ending in `/cited-path` (suffix), then files whose basename matches.
 * The first tier with any match wins; extra matches at that tier come back
 * as `others` so the UI can mention them in the tooltip. Built once per file
 * list; the returned function only reads the index (a render — including
 * every streamed token — then costs a couple of map lookups per mention).
 */
export const createCitationResolver = (filePaths: string[]): CitationResolver => {
  const exact = new Set(filePaths);
  // Segment-aligned suffixes: 'src/app.py' indexes 'app.py' (from the first
  // slash on), so tier 2 and the basename tier are both one lookup.
  const bySuffix = new Map<string, string[]>();
  const byBase = new Map<string, string[]>();

  const push = (index: Map<string, string[]>, key: string, path: string) => {
    const bucket = index.get(key);
    if (bucket) bucket.push(path);
    else index.set(key, [path]);
  };

  for (const path of filePaths) {
    const base = path.slice(path.lastIndexOf("/") + 1);
    push(byBase, base, path);
    let slash = path.indexOf("/");
    while (slash !== -1) {
      push(bySuffix, path.slice(slash + 1), path);
      slash = path.indexOf("/", slash + 1);
    }
  }

  return (citedPath: string): ResolvedCitation | null => {
    const normalized = citedPath.replace(/\\/g, "/");

    // Tier 1: the exact path. Longer paths that merely end with it are
    // ambiguity candidates, not competitors.
    if (exact.has(normalized)) {
      return { path: normalized, others: bySuffix.get(normalized) ?? [] };
    }

    // Tier 2: unique path suffix (bucket also covers tier 3's basename —
    // a bare name is the shortest suffix a root-level file can produce).
    const suffixHits = bySuffix.get(normalized);
    if (suffixHits && suffixHits.length > 0) {
      return { path: suffixHits[0], others: suffixHits.slice(1) };
    }

    // Tier 3: unique basename of the cited string.
    const base = normalized.slice(normalized.lastIndexOf("/") + 1);
    const baseHits = byBase.get(base);
    if (baseHits && baseHits.length > 0) {
      return { path: baseHits[0], others: baseHits.slice(1) };
    }

    return null;
  };
};
