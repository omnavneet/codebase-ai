import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import type { CitationClickHandler, ResolvedCitation } from './citationUtils';
import { createCitationResolver } from './citationUtils';
import { copyText } from '../utils/clipboard';
import type { Citation } from '../pages/chat/types';

type ResolveCitation = (path: string, start: number, end: number) =>
  (ResolvedCitation & { fileId: string }) | null;

// Citations the agent emits: `path/to/File.ext:12`, `File.ext:12-34`, and
// `File.ext (line 12)`. One alternation so a single pass finds every form.
const CITATION_EXTS =
  'java|py|js|ts|jsx|tsx|css|html|md|json|go|rs|c|cpp|h|cs|rb|php|kt|scala|sh|yml|yaml';
const CITATION_SOURCE = new RegExp(
  `([\\w./-]+\\.(?:${CITATION_EXTS})):(\\d+)(?:-(\\d+))?` +
    `|([\\w./-]+\\.(?:${CITATION_EXTS})) \\(line (\\d+)\\)`,
  'g',
);

const splitText = (
  text: string,
  onCitationClick: CitationClickHandler,
  resolve: ResolveCitation,
): React.ReactNode[] => {
  const parts: React.ReactNode[] = [];
  // Fresh copy per call so one paragraph's lastIndex never leaks into the next.
  const pattern = new RegExp(CITATION_SOURCE.source, 'g');
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  const push = (node: React.ReactNode) => {
    parts.push(<React.Fragment key={parts.length}>{node}</React.Fragment>);
  };

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      push(text.slice(lastIndex, match.index));
    }

    // Groups 1-3: `path:12-34` form; groups 4-6: `path (line 12)` form.
    const citedPath = match[1] ?? match[4];
    const startLine = parseInt(match[2] ?? match[5], 10);
    const endLine = match[3] ? parseInt(match[3], 10) : startLine;
    const resolved = startLine > 0 && endLine >= startLine ? resolve(citedPath, startLine, endLine) : null;

    if (resolved) {
      const base = resolved.path.split('/').pop() ?? resolved.path;
      const tooltip =
        resolved.others.length > 0
          ? `Open ${base} at line ${startLine} — also matches ${resolved.others.join(', ')}`
          : `Open ${base} at line ${startLine}`;
      parts.push(
        <button
          key={parts.length}
          type="button"
          className="inline-citation"
          title={tooltip}
          onClick={() => onCitationClick(resolved.path, startLine, endLine, resolved.fileId)}
        >
          {match[0]}
        </button>,
      );
    } else {
      push(<span title="This source could not be verified" className="unverified-citation">{match[0]}</span>);
    }

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    push(text.slice(lastIndex));
  }
  return parts;
};

/**
 * Walk markdown-rendered React children and turn citation-looking text into
 * buttons. Recurses into inline elements (strong/em/inline code/…) so
 * citations anywhere in prose or inline code become clickable, but leaves
 * `pre` untouched — the `pre` component never runs this walk, so fenced code
 * blocks keep their literals (a path inside a block is a sample, not a
 * reference).
 */
const injectCitations = (
  children: React.ReactNode,
  onCitationClick: CitationClickHandler,
  resolve: ResolveCitation,
): React.ReactNode =>
  React.Children.map(children, (child) => {
    if (typeof child === 'string') {
      return <React.Fragment>{splitText(child, onCitationClick, resolve)}</React.Fragment>;
    }
    if (React.isValidElement(child)) {
      const el = child as React.ReactElement<{ children?: React.ReactNode }>;
      if (el.type === 'pre') {
        return child;
      }
      if (el.props && el.props.children !== undefined) {
        return React.cloneElement(
          el,
          {},
          injectCitations(el.props.children, onCitationClick, resolve),
        );
      }
    }
    return child;
  });

// Block-level elements whose text should carry clickable citations.
const BLOCK_TAGS = [
  'p', 'li', 'td', 'th', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
] as const;

/**
 * A fenced code block with a Copy control (the code only, never the button).
 * Reads the text back from the rendered <pre>, so every language and quirk of
 * the markdown pipeline is copied verbatim. "Copied" shows for ~1.5s.
 */
const CodeBlock: React.FC<Record<string, unknown>> = (props) => {
  const { children, ...rest } = props as { children?: React.ReactNode } & Record<string, unknown>;
  delete (rest as Record<string, unknown>).node;
  const preRef = React.useRef<HTMLPreElement>(null);
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    const code = preRef.current?.textContent ?? '';
    if (!code || !(await copyText(code))) return;
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="md-code-block">
      <button type="button" className="md-code-copy" onClick={handleCopy}>
        {copied ? 'Copied' : 'Copy'}
      </button>
      <pre ref={preRef} {...(rest as object)}>{children}</pre>
    </div>
  );
};

const buildComponents = (
  onCitationClick: CitationClickHandler,
  resolve: ResolveCitation,
) => {
  const components: Record<string, React.ComponentType<Record<string, unknown>>> = {
    pre: CodeBlock,
  };
  for (const tag of BLOCK_TAGS) {
    const Tag = tag as keyof React.JSX.IntrinsicElements;
    // Strip the hast AST (`node`) so it is never spread onto the DOM.
    components[tag] = (props) => {
      const { children, ...rest } = props as { children?: unknown } & Record<string, unknown>;
      delete (rest as Record<string, unknown>).node;
      return (
        <Tag {...(rest as object)}>
          {injectCitations(children as React.ReactNode, onCitationClick, resolve)}
        </Tag>
      );
    };
  }
  return components;
};

interface MarkdownAnswerProps {
  content: string;
  onCitationClick: CitationClickHandler;
  evidence?: Citation[];
  verifiedCitations?: boolean;
  className?: string;
}

const MarkdownAnswer: React.FC<MarkdownAnswerProps> = ({
  content,
  onCitationClick,
  evidence,
  verifiedCitations,
  className,
}) => {
  const resolve = React.useMemo<ResolveCitation>(() => {
    if (!evidence?.length) return () => null;
    if (verifiedCitations) {
      return (path, start, end) => {
        const source = evidence.find(item => item.file_id && item.start_line === start && item.end_line === end
          && (item.reference_paths ?? [item.file_path]).includes(path));
        return source?.file_path && source.file_id
          ? { path: source.file_path, fileId: source.file_id, others: [] } : null;
      };
    }
    const resolvePath = createCitationResolver(
      [...new Set(evidence.flatMap(source => source.file_path ? [source.file_path] : []))],
    );
    return (path, start, end) => {
      const resolved = resolvePath(path);
      if (!resolved) return null;
      const source = evidence.find(item =>
        item.file_id && item.file_path === resolved.path && item.start_line <= start && item.end_line >= end,
      );
      return source?.file_id ? { ...resolved, fileId: source.file_id } : null;
    };
  }, [evidence, verifiedCitations]);

  return (
    <div className={['markdown-body', className].filter(Boolean).join(' ')}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={buildComponents(onCitationClick, resolve) as never}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default MarkdownAnswer;
