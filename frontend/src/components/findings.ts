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
