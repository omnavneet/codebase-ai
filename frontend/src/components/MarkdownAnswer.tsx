import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import type { CitationClickHandler } from './citationUtils';

// `path.ext:12` or `path.ext:12-34`. Mirrors the citation forms the agent emits.
const CITATION_SOURCE =
  /([\w./-]+\.(?:java|py|js|ts|jsx|tsx|css|html|md|json|go|rs|c|cpp|h|cs|rb|php|kt|scala|sh|yml|yaml)):(\d+)(?:-(\d+))?/;

/** Split a text node into plain runs and clickable `file.ext:line` buttons. */
const splitText = (
  text: string,
  onCitationClick: CitationClickHandler,
): React.ReactNode[] => {
  const parts: React.ReactNode[] = [];
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

    const filePath = match[1];
    const startLine = parseInt(match[2], 10);
    const endLine = match[3] ? parseInt(match[3], 10) : startLine;
    parts.push(
      <button
        key={parts.length}
        type="button"
        className="inline-citation"
        onClick={() => onCitationClick(filePath, startLine, endLine)}
      >
        {match[0]}
      </button>,
    );

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    push(text.slice(lastIndex));
  }
  return parts;
};

/**
 * Walk markdown-rendered React children and turn citation-looking text into
 * buttons. Recurses into inline elements (strong/em/a/…) so citations anywhere
 * in prose become clickable, but leaves `code`/`pre` untouched — a path inside
 * code is a literal, not a reference to open.
 */
const injectCitations = (
  children: React.ReactNode,
  onCitationClick: CitationClickHandler,
): React.ReactNode =>
  React.Children.map(children, (child) => {
    if (typeof child === 'string') {
      return <React.Fragment>{splitText(child, onCitationClick)}</React.Fragment>;
    }
    if (React.isValidElement(child)) {
      const el = child as React.ReactElement<{ children?: React.ReactNode }>;
      if (el.type === 'code' || el.type === 'pre') {
        return child;
      }
      if (el.props && el.props.children !== undefined) {
        return React.cloneElement(el, {}, injectCitations(el.props.children, onCitationClick));
      }
    }
    return child;
  });

// Block-level elements whose text should carry clickable citations.
const BLOCK_TAGS = [
  'p', 'li', 'td', 'th', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
] as const;

const buildComponents = (onCitationClick: CitationClickHandler) => {
  const components: Record<string, React.ComponentType<Record<string, unknown>>> = {};
  for (const tag of BLOCK_TAGS) {
    const Tag = tag as keyof React.JSX.IntrinsicElements;
    // Strip the hast AST (`node`) so it is never spread onto the DOM.
    components[tag] = (props) => {
      const { children, ...rest } = props as { children?: unknown } & Record<string, unknown>;
      delete (rest as Record<string, unknown>).node;
      return (
        <Tag {...(rest as object)}>
          {injectCitations(children as React.ReactNode, onCitationClick)}
        </Tag>
      );
    };
  }
  return components;
};

interface MarkdownAnswerProps {
  content: string;
  onCitationClick: CitationClickHandler;
  className?: string;
}

/**
 * Renders an LLM answer as GFM markdown while keeping `file.ext:line` citations
 * clickable. Used by both the chat stream and the agent/explain/debug panels so
 * every answer formats identically.
 */
const MarkdownAnswer: React.FC<MarkdownAnswerProps> = ({
  content,
  onCitationClick,
  className,
}) => (
  <div className={['markdown-body', className].filter(Boolean).join(' ')}>
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={buildComponents(onCitationClick) as never}
    >
      {content}
    </ReactMarkdown>
  </div>
);

export default MarkdownAnswer;