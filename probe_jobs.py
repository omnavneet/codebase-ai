import psycopg2
c = psycopg2.connect(host="localhost", dbname="codebase_ai",
                     user="admin", password="my-dev-password-111")
cur = c.cursor()
cur.execute(
    "SELECT id::text, status, progress_percent, attempts, locked_by,"
    " error_message, content_hash, created_at"
    " FROM indexing_jobs ORDER BY created_at DESC LIMIT 5")
for r in cur.fetchall():
    print(r[0], "|", r[1], "|", r[2], "|", r[3], "|", r[4], "|",
          (r[5] or "")[:150], "|", (r[6] or "")[:12], "|", r[7])
