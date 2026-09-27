export interface Finding {
  severity: string;
  category: string;
  title: string;
  lines: string;
  description: string;
  suggestion: string;
  code_before?: string | null;
  code_after?: string | null;
}

export const SEVERITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 };

export const sortFindings = (findings: Finding[]): Finding[] =>
  [...findings].sort(
    (a, b) => (SEVERITY_ORDER[a.severity] ?? 99) - (SEVERITY_ORDER[b.severity] ?? 99),
  );