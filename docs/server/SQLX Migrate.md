## 1. sqlx migrate add <name> (e.g., sqlx migrate add init_schema)

When you run this command, SQLx doesn't touch your database at all. All it does is create a brand new, empty .sql file
in a folder named migrations/ inside your project.

However, it prefixes the file with the exact current timestamp (e.g., 20260902221500_init_schema.sql). Why the
timestamp? Because chronological order is critical. If you have 10 migrations, SQLx needs to know exactly which order to
run them in so that tables are created before data is inserted into them.

## 2. sqlx migrate run

This is where the magic happens. When you run this command in your terminal, SQLx does a specific sequence of actions:

Connects: It reads your .env file to get the DATABASE_URL and logs into Postgres. The Secret Table: It looks for a
special, hidden table in your database called _sqlx_migrations. If it doesn't exist, it creates it. The Audit: It looks
inside _sqlx_migrations to see a list of every migration that has already been run. The Execution: It looks at the .sql
files in your migrations/ folder on your computer. If it sees a file (say, 20260902221500_init_schema.sql) that is not
listed in the hidden table, it reads the SQL inside that file and executes it on the database. The Record: If the SQL
succeeds without crashing, it writes the file's timestamp and a "checksum" (a cryptographic hash of the file's contents)
into the _sqlx_migrations table. Because of this system, you can run sqlx migrate run 100 times in a row, and it will
only ever execute new migrations exactly once!

## 3. What about sqlx::migrate!().run (&pool).await inside the Rust code?

You might be wondering: "If I use the CLI to run my migrations, why did we put that macro inside main.rs?"

The sqlx::migrate!("./migrations") macro is one of the coolest features of Rust. During compile time (when you run cargo
build), that macro reads all the .sql files in your migrations/ folder and physically embeds the text of those files
directly into your final .exe binary.

When your Rust app boots up, it executes the exact same logic as sqlx migrate run (checking the hidden table and
applying new SQL).