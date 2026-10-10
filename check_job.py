import psycopg2
c = psycopg2.connect(host="localhost", port=5432, dbname="codebase_ai",
                     user="admin", password="my-dev-password-111")
cur = c.cursor()
cur.execute("select id,status,progress_percent,attempts,locked_by,"
            "locked_at,next_run_at,error_message from indexing_jobs "
            "order by created_at desc limit 5")
for r in cur.fetchall():
    print(r)
