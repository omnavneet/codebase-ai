/**
 * Relative time for "3h ago" style labels.
 *
 * A tiny helper rather than a library: the dashboard and the chat sidebar
 * both need the same handful of buckets, and the buckets are what people
 * actually read ("3h ago" beats "2024-05-03 14:22").
 */

/**
 * Formats a timestamp as a short relative label: "just now", "5 min ago",
 * "3 hr ago", "2 days ago", then an absolute date once it stops being useful.
 */
export const formatRelativeTime = (dateString: string): string => {
  const parsed = new Date(dateString);
  if (Number.isNaN(parsed.getTime())) return '';
  const now = Date.now();
  const diffMins = Math.floor((now - parsed.getTime()) / 60_000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);
  if (diffMins < 1) return 'just now';
  if (diffMins < 60) return `${diffMins} min ago`;
  if (diffHours < 24) return `${diffHours} hr ago`;
  if (diffDays < 7) return `${diffDays} day ago`;
  if (diffDays < 30) {
    return parsed.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
  return parsed.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
};

/**
 * The timestamp to report for a project: when it was last touched, falling
 * back to when it was created for records that predate that field.
 */
export const projectTimestamp = (
  updatedAt: string | undefined,
  createdAt: string,
): string => updatedAt || createdAt;
