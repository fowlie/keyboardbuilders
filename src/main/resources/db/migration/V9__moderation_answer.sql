-- A moderator's answer to one of the intake bot's questions. Backlog #117.
--
-- ONE WRITER PER STORE: the bot writes the questions to a file the app only reads, and the
-- app writes answers here, which the bot only reads. `question_id` is the bot's own key
-- (`kind:slug:fingerprint`) and `reply` is exactly what a reply on the old GitHub issue
-- would have said (`3`, `none`, `y`), so the bot's answer parsers read it unchanged.
CREATE TABLE moderation_answer (
    question_id TEXT PRIMARY KEY,
    reply       TEXT NOT NULL,
    by_login    TEXT NOT NULL,
    at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);
